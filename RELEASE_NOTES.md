Papyrus 1.0.0 modernizes the original JavaFX and SQLite library manager.

- French, English, and Arabic, with right-to-left Arabic layouts.
- Light and dark appearances, saved between sessions.
- A new logo, matching window icons, and an animated login image carousel.
- Scrollable document covers and redesigned creation forms with searchable suggestions.
- Documents, students, staff, categories, types, and loan history.
- Real dashboard totals, search and document filters, loans and returns.
- Administrator and librarian accounts, with staff management restricted to administrators.
- First-run administrator setup, password hashing, and attributed activity history.
- Windows installer, Linux Debian package, and portable downloads with a bundled Java runtime.

On a new installation, create an administrator account when Papyrus opens. There is no default password.

Windows: run `Papyrus-1.0.0-windows-x64.exe`, or extract the portable ZIP and open `Papyrus.exe`.

Ubuntu/Debian: install the `.deb` using your package manager. For the portable Linux archive, extract it and run `Papyrus/bin/Papyrus`. A graphical desktop with GTK 3 is required.

These packages are unsigned. Windows may display a publisher warning. Check the SHA256SUMS file against your download.

Existing installations: back up your original database and images before upgrading. See README.md for the legacy database location and image migration instructions. Existing IDs and records are retained; legacy passwords are upgraded to hashes on successful sign-in.
