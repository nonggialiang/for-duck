# Iceberg REST Server - OPA Authorization Policy
# Phase 6: Rego policy for OPA-based authorization.
# Deploy to OPA at package: iceberg.rest

package iceberg.rest

import future.keywords.if
import future.keywords.in

default allow := false
default credential_privilege := null

# =============================================================================
# Owner-based authorization: owner has full access to the resource
# =============================================================================

allow if {
    is_owner(input.user, input.resource)
}

# =============================================================================
# Credential privilege for owner
# =============================================================================

credential_privilege := "write" if {
    is_owner(input.user, input.resource)
}

# =============================================================================
# Operation-specific authorization rules
# =============================================================================

# READ operations (load, list, exists, plan_scan)
allow if {
    is_read_operation(input.action)
    has_privilege(input.user, "select", input.resource)
    not has_deny(input.user, "select", input.resource)
}

credential_privilege := "read" if {
    is_read_operation(input.action)
    has_privilege(input.user, "select", input.resource)
}

# WRITE operations (create, update, drop, replace)
allow if {
    is_write_operation(input.action)
    has_privilege(input.user, "modify", input.resource)
    not has_deny(input.user, "modify", input.resource)
}

credential_privilege := "write" if {
    is_write_operation(input.action)
    has_privilege(input.user, "modify", input.resource)
}

# CREATE operations on schemas/tables/views
allow if {
    is_create_operation(input.action)
    has_privilege(input.user, "create", input.resource)
    not has_deny(input.user, "create", input.resource)
}

credential_privilege := "write" if {
    is_create_operation(input.action)
    has_privilege(input.user, "create", input.resource)
}

# =============================================================================
# Cross-namespace rename: source OWNER + destination CREATE privilege
# =============================================================================

allow if {
    input.action == "rename_table"
    is_owner(input.user, input.resource)
}

allow if {
    input.action == "rename_view"
    is_owner(input.user, input.resource)
}

# =============================================================================
# Helper functions
# =============================================================================

is_owner(user, resource) if {
    key := concat("/", [resource.catalog, resource.schema, resource.name, user])
    data.iceberg.rest.owners[key]
}

# Operations that only read data
is_read_operation(action) if {
    action in [
        "load_table", "load_table_credential", "load_namespace",
        "list_table", "list_namespace", "list_view",
        "table_exists", "namespace_exists", "view_exists",
        "plan_table_scan"
    ]
}

# Operations that modify data
is_write_operation(action) if {
    action in [
        "update_table", "drop_table", "update_namespace",
        "drop_namespace", "drop_view", "replace_view"
    ]
}

# Operations that create new resources
is_create_operation(action) if {
    action in [
        "create_table", "create_namespace", "create_view",
        "register_table"
    ]
}

# Check if user has a specific privilege on a resource
has_privilege(user, privilege, resource) if {
    key := concat("/", [user, privilege, resource.catalog, resource.schema, resource.name])
    data.iceberg.rest.privileges[key]
}

# Check if user is denied a specific privilege
has_deny(user, privilege, resource) if {
    key := concat("/", [user, privilege, "deny", resource.catalog, resource.schema, resource.name])
    data.iceberg.rest.denies[key]
}
