# Papyrus

A desktop library manager built with JavaFX and SQLite. Available in **French, English, and Arabic**, with light and dark themes and a right-to-left Arabic layout.

Manage documents with a scrollable cover gallery, students, loans, staff, categories, and document types. The overview shows actual collection and loan totals; document, student, and staff histories retain previous loans. Administrators can manage accounts and inspect the activity log.

![Papyrus document cover gallery](docs/screenshots/documents-en-light.png)

![Arabic overview with the dark theme](docs/screenshots/dashboard-ar-dark.png)

## Install

Download the Windows or Linux x64 package from [Releases](https://github.com/ayoub-ghanaj/papyrus/releases). Packages include the Java runtime; users do not need to install Java or Maven.

| System | Installer | Portable version |
| --- | --- | --- |
| Windows 10/11 x64 | Run the `.exe`; choose the installation folder and shortcuts. | Extract the ZIP and open `Papyrus/Papyrus.exe`. |
| Ubuntu/Debian x64 | Install the `.deb` with your package manager, or `sudo apt install ./Papyrus-1.0.1-linux-x64.deb`. | Extract the `.tar.gz` and run `Papyrus/bin/Papyrus`. GTK 3 and a graphical desktop are required. |

On first launch, create an administrator account. There is no default password. Existing installations keep their existing sign-in details.

The packages are unsigned; Windows may show a publisher warning. Release downloads include SHA-256 checksums. macOS and ARM builds are outside this release's scope.

## Everyday use

![Papyrus login carousel](docs/screenshots/login-fr-light.png)

- The login screen cycles through three local library images. Use its pause and navigation controls to control the slideshow.
- Switch language and appearance using the controls at the top of the window. Both settings are saved.
- Add a category and type, then add documents with optional cover images. Filter the gallery by title, category, type, and availability.
- Type in document, student, category, and type selectors to see matching suggestions. Find students by ID, name, phone, or email; use the arrow keys and Enter to choose a result. French accents and Arabic diacritics are handled during search.
- Register a student, then create a loan from the gallery or Loans screen. The return date must be after today.
- Mark loans as returned to make documents available again. Include returned loans to see history.
- Double-click a document card or a student/staff row to edit it. Select a record for its history or deletion actions.
- Return outstanding loans before deleting their document or student. Deleted records remain in historical loans.
- Staff account management and the activity screen are restricted to administrators. The last administrator and your own account are protected against deletion or role changes.

## Data and upgrades

Everything is stored locally. The default data folder is:

| System | Folder |
| --- | --- |
| Windows | `%APPDATA%\Papyrus` |
| Linux | `$XDG_DATA_HOME/papyrus`, or `~/.local/share/papyrus` |

The folder contains `test1.db`, imported images in `images/`, and `settings.properties`. Close Papyrus and back up the **entire folder**. Uninstalling the app does not intentionally delete this folder.

The original application's tables and IDs are retained. Before using an old database, back up its database **and original image files**. You can copy `test1.db` into the default data folder, or launch from the original project folder containing `database/test1.db`; Papyrus recognizes that legacy location. For a custom location, pass `-Dpapyrus.dataDir=/absolute/data/folder` to Java when running from source.

The first opening of a legacy database creates `test1.pre-1.0.0.db` as a database-only backup before initializing missing tables or upgrading passwords. Old cleartext passwords are upgraded to salted PBKDF2 hashes on successful login. Keep image files at their existing locations, or reselect them in the editor to import a copy into the new data folder. The original repository's `resources/img/` is retained for legacy file references and is not bundled into installers.

## Build and run

Install JDK 25. The Maven wrapper downloads Maven 3.10.0 on first use.

```sh
./mvnw clean verify
./mvnw javafx:run
```

On Windows, use `mvnw.cmd` in place of `./mvnw`. The build pins JavaFX 27 and SQLite JDBC 3.53.4.0. IDEs can import `pom.xml` directly.

The test suite checks persistence, legacy password upgrades, Unicode and apostrophes, permission boundaries, loan availability, audit attribution, and translation coverage. To exercise the actual UI on a graphical desktop after building:

```sh
java --enable-native-access=ALL-UNNAMED -cp 'target/test-classes:target/classes:target/lib/*' papyrus.UiSmoke
```

Use `;` instead of `:` in the class path on Windows. The UI exercise uses a separate temporary database and writes screenshots under `target/screenshots/`. On a display without a window manager, add `-Dpapyrus.smoke.skipWindowManager=true` to skip native maximize/fullscreen checks.

## Build installers

Run the following on each target operating system with JDK 25 and Python 3.11+:

```sh
python scripts/package.py
```

Windows needs WiX Toolset 3.14.1 or another version supported by JDK 25 `jpackage`. Debian packaging needs `fakeroot` and `dpkg-deb`. The script builds a trimmed Java runtime, a native installer, a portable archive, and checksums under `dist/`. `--portable-only` skips installer tooling. Neither package includes local databases, demo accounts, or the original repository's photos.

GitHub Actions builds and tests Windows and Linux packages. Tags matching `v*` prepare a **draft** GitHub release after both platform builds pass. Review the draft and installers before publishing. Keep the version in `pom.xml`, `RELEASE_NOTES.md`, and UI version labels in sync for each release.

The project uses JavaFX, SQLite JDBC, SLF4J, and a Java runtime. See [THIRD_PARTY.md](THIRD_PARTY.md) for component notices.
