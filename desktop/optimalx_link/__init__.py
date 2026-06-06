"""OptimalX Link desktop client.

Spec: ../app/docs/architecture/OPTIMALX_LINK.md
Plan: ../app/docs/implementation/OPTIMALX_LINK_IMPLEMENTATION_PLAN.md (Phase 3+).
"""

# Bump when the wire protocol changes (new endpoint shape, new auth scheme).
# Must stay in sync with `LINK_PROTOCOL_VERSION` in the Android side
# (`app/src/main/java/com/example/optimalx/data/link/LinkServer.kt`).
LINK_PROTOCOL_VERSION: int = 1

# Default port the desktop's embedded server listens on. The phone server
# defaults to 17832; we pick 17833 so both can run on a single LAN without
# colliding when port-forwarded.
DESKTOP_DEFAULT_PORT: int = 17833

# Filename of the archive metadata block. Mirrors `BackupArchive.MANIFEST_ENTRY`
# on the Android side. If you change this here, change it there too.
MANIFEST_ENTRY: str = "manifest.json"

# Zip-entry prefixes inside a snapshot archive. Mirrors `BackupArchive`
# constants on the Android side (DB_DIR_PREFIX, FILES_ATTACHMENTS_PREFIX,
# FILES_WORKSHOP_PREFIX).
DB_DIR_PREFIX: str = "database/"
FILES_ATTACHMENTS_PREFIX: str = "files/optimalx_files/"
FILES_WORKSHOP_PREFIX: str = "files/workshop/"

# Format version of the on-disk archive. Mirrors
# `BackupManifest.CURRENT_FORMAT_VERSION` on the Android side. Bump only when
# the on-disk layout itself changes; the wire protocol version above is
# orthogonal.
CURRENT_FORMAT_VERSION: int = 1
