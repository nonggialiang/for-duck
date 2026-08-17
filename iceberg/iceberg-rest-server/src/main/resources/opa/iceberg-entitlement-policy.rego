# Iceberg REST Server - OPA Entitlement Row Filter Policy
# Deploy to OPA at package: iceberg.entitlement
#
# This policy is intentionally independent of the authorization policy
# (iceberg-rest-policy.rego, package iceberg.rest): it only resolves the
# row-filter SQL for a user/table pair and never influences allow decisions.
#
# Entitlement data is managed out-of-band by administrators via the OPA Data API:
#
#   PUT /v1/data/iceberg/entitlement/filters/alice/cat/db/t1
#   body: "region = 'US'"   (a JSON string containing the row filter SQL)
#
#   DELETE /v1/data/iceberg/entitlement/filters/alice/cat/db/t1

package iceberg.entitlement

import future.keywords.if

default row_filter := null

row_filter := filter if {
	key := concat("/", [input.user, input.resource.catalog, input.resource.schema, input.resource.name])
	filter := data.iceberg.entitlement.filters[key]
}
