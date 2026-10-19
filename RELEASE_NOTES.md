Papyrus 1.1.0 adds recovery tools and improves library workflows and responsiveness.

- Account deletion, role changes, and password changes revoke existing sessions. Failed sign-ins leave no authenticated session.
- Back up accounts, records, and images to a verified ZIP. Restore keeps a recovery backup first and requires other windows using the library to be closed.
- Imported images use portable paths. Existing managed images can be recovered after moving a library folder.
- Restore archived records with their original identifiers and history.
- Record authors, ISBNs, shelf locations, and unique copy/barcode identifiers. Add categories and types from document forms.
- Renew loans, record actual return timestamps, filter overdue loans, export the visible list, and print overdue reports.
- Import documents and students from UTF-8 CSV with preview and all-or-nothing validation. Export active records or download a template.
- Change your own password from Settings.
- Database work runs in the background. Indexed loan queries, debounced searches, and reusable gallery rows keep larger catalogs responsive.
- Navigation scrolls on small windows while account controls remain visible. Editing preserves search/filter state and detects conflicting edits.
- English, French, Arabic, light, and dark interfaces include the new workflows.
- CI checks release versions and exercises native installation, upgrade from 1.0.0, migration, and uninstall data preservation on Windows and Linux.

Existing databases are migrated without changing record IDs. Before upgrading a 1.0.x database, Papyrus keeps `test1.pre-1.1.0.db`; this snapshot contains the database only. Keep your own full backup of the library folder and original images as well. After opening a migrated library, use Papyrus 1.1.0 or later.

Windows: use the EXE installer or extract the portable ZIP. Ubuntu/Debian: install the DEB or extract the portable archive. Java is bundled. Packages are unsigned; verify the supplied SHA-256 checksums.
