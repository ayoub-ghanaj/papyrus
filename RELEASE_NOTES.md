Papyrus 1.0.1 updates the application dependencies and release tooling.

- JavaFX 27 and SLF4J 2.0.20.
- JUnit 6.1.3 and Maven 3.10.0, with updated clean, resources, compiler, test, JAR, and dependency plugins.
- Updated GitHub Actions, pinned to verified commit hashes.
- Packaged artifacts are downloaded again in CI and checked against their SHA-256 lists.
- Dependabot groups Maven and GitHub Actions updates to reduce separate update PRs.

SQLite JDBC remains at 3.53.4.0, the current stable release checked for this update. This patch does not change the database schema or user workflows.

Windows: run `Papyrus-1.0.1-windows-x64.exe`, or extract the portable ZIP and open `Papyrus/Papyrus.exe`.

Ubuntu/Debian: install the `.deb` using your package manager. For the portable Linux archive, extract it and run `Papyrus/bin/Papyrus`. A graphical desktop with GTK 3 is required.

Both platforms include the Java runtime. Packages are unsigned; check the accompanying SHA-256 checksum list. Existing accounts, records, images, and preferences remain in their data folder.
