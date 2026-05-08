import csv
import re
import s3fs
import pyarrow as pa
import pyarrow.parquet as pq
from datetime import date, datetime
from decimal import Decimal
from typing import Annotated, Dict, Any, List
from pydantic import create_model, Field, StringConstraints, ValidationError, field_validator
from pyiceberg.catalog import load_catalog

# --- 1. 动态 Schema 与 Pydantic 解析层 ---

def parse_config_type(type_str: str):
    """解析配置字符串，如 'varchar(50)' 或 'numeric(10,2)'"""
    match = re.match(r"(\w+)(?:\((\d+)(?:,(\d+))?\))?", type_str.lower())
    if not match: return type_str, None, None
    base, p1, p2 = match.group(1), match.group(2), match.group(3)
    return base, (int(p1) if p1 else None), (int(p2) if p2 else None)

def get_pydantic_field(type_str: str):
    """映射配置到 Pydantic 类型"""
    base, p1, p2 = parse_config_type(type_str)
    if base in ('char', 'varchar'): return (Annotated[str, StringConstraints(max_length=p1)], ...)
    if base in ('numeric', 'number'):
        if p2 is not None: return (Annotated[Decimal, Field(max_digits=p1, decimal_places=p2)], ...)
        return (float, ...)
    if base == 'date': return (date, ...)
    if base == 'datetime': return (datetime, ...)
    return (Any, ...)

def get_arrow_type(type_str: str):
    """映射配置到 PyArrow 类型，用于生成 Parquet Schema"""
    base, p1, p2 = parse_config_type(type_str)
    if base in ('char', 'varchar'): return pa.string()
    if base in ('numeric', 'number'):
        if p2 is not None: return pa.decimal128(p1, p2)
        return pa.float64()
    if base == 'date': return pa.date32()
    if base == 'datetime': return pa.timestamp('us')
    return pa.string()

def custom_date_parser(v):
    """通用日期解析"""
    if isinstance(v, (date, datetime)): return v
    for fmt in ("%Y/%m/%d", "%Y-%m-%d", "%Y%m%d", "%Y-%m-%d %H:%M:%S"):
        try: return datetime.strptime(v, fmt)
        except: continue
    raise ValueError(f"Unknown date format: {v}")

# --- 2. 核心流式处理引擎 ---

def run_streaming_etl(csv_path, field_config, temp_s3_path, s3_opts):
    # 1. 初始化模型与 Schema
    DynamicModel = create_model(
        "DynamicRow", 
        **{n: get_pydantic_field(t) for n, t in field_config.items()},
        __validators__={"dv": field_validator(*[n for n, t in field_config.items() if 'date' in t], mode='before')(custom_date_parser)}
    )
    expected_cols = list(field_config.keys())
    arrow_schema = pa.schema([(n, get_arrow_type(t)) for n, t in field_config.items()])

    # 2. 准备 S3 文件系统
    fs = s3fs.S3FileSystem(**s3_opts)
    
    valid_count = 0
    error_logs = []
    chunk = []
    chunk_size = 5000  # 内存缓冲区大小

    with open(csv_path, 'r', encoding='utf-8') as f_in:
        reader = csv.reader(f_in)
        
        # 开启 Parquet 流式写入器
        with fs.open(temp_s3_path, 'wb') as f_out:
            with pq.ParquetWriter(f_out, arrow_schema) as writer:
                for line_num, row in enumerate(reader, 1):
                    if not row: continue
                    prefix, content = row[0].lower(), row[1:]

                    if prefix == 'd':
                        # 长度校验
                        if len(content) != len(expected_cols):
                            error_logs.append({"line": line_num, "err": "Col count mismatch"})
                            continue
                        
                        try:
                            # Pydantic 校验与转换
                            val_obj = DynamicModel(**dict(zip(expected_cols, content)))
                            chunk.append(val_obj.model_dump())
                            valid_count += 1
                            
                            # 缓冲区满，刷入 MinIO
                            if len(chunk) >= chunk_size:
                                writer.write_table(pa.Table.from_pylist(chunk, schema=arrow_schema))
                                chunk = []
                        except ValidationError as e:
                            error_logs.append({"line": line_num, "err": "Validation fail", "detail": e.errors()})

                # 写入尾部残余数据
                if chunk:
                    writer.write_table(pa.Table.from_pylist(chunk, schema=arrow_schema))

    return temp_s3_path, error_logs

# --- 3. Iceberg 高效落地 ---

def load_temp_to_iceberg(temp_s3_path, table_name, catalog_opts, s3_opts):
    catalog = load_catalog("default", **catalog_opts)
    table = catalog.load_table(table_name)
    ice_arrow_schema = table.schema().as_arrow()

    fs = s3fs.S3FileSystem(**s3_opts)
    with fs.open(temp_s3_path, 'rb') as f:
        # 1. 读取 Parquet (带 Schema)
        arrow_table = pq.read_table(f)
        
        # 2. 自动裁剪与类型强制对齐 (Cast)
        # 只选取表中存在的列
        present_cols = [n for n in arrow_table.column_names if n in ice_arrow_schema.names]
        arrow_table = arrow_table.select(present_cols)
        
        # 强制 Cast 为 Iceberg 表的物理 Schema 类型（确保精度完全一致）
        arrow_table = arrow_table.cast(ice_arrow_schema)

        # 3. 原子追加到 Iceberg
        table.append(arrow_table)
    print(f"Done: Data from {temp_s3_path} committed to Iceberg.")

# --- 4. 执行主流程 ---

if __name__ == "__main__":
    CONFIG = {"id": "numeric(10,0)", "name": "varchar(20)", "price": "numeric(12,2)", "create_date": "date"}
    S3_OPTS = {"endpoint_url": "http://minio:9000", "key": "admin", "secret": "password"}
    CATALOG_OPTS = {**S3_OPTS, "uri": "http://iceberg-rest:8181", "type": "rest"}
    
    # 步骤 1: 流式解析 CSV -> MinIO Parquet (恒定内存)
    tmp_path, errors = run_streaming_etl("data.csv", CONFIG, "s3://tmp/data.parquet", S3_OPTS)
    
    # 步骤 2: Parquet -> Iceberg (高效块读取)
    load_temp_to_iceberg(tmp_path, "db.target_table", CATALOG_OPTS, S3_OPTS)
    
    # 步骤 3: 处理错误日志
    if errors:
        print(f"Captured {len(errors)} errors.")

