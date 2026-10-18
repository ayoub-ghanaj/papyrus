# Third-party components

Papyrus release packages include the following components. Their license files are retained in the dependency JARs and runtime `legal/` directory.

| Component | License and source |
| --- | --- |
| OpenJFX / JavaFX | [GPL v2 with Classpath Exception](https://github.com/openjdk/jfx/blob/master/LICENSE), [OpenJFX](https://openjfx.io/) |
| OpenJDK runtime (Temurin in CI) | [GPL v2 with Classpath Exception](https://github.com/openjdk/jdk/blob/master/LICENSE), [Eclipse Temurin](https://adoptium.net/) |
| SQLite JDBC | [Apache 2.0 and BSD licenses](https://github.com/xerial/sqlite-jdbc/blob/master/LICENSE), [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc) |
| SQLite database engine | [Public domain](https://sqlite.org/copyright.html) |
| SLF4J | [MIT license](https://www.slf4j.org/license.html) |

Maven, JUnit, Python, and WiX are build or test tools; the tool distributions are not included in the application payload. The installer is generated with the WiX Toolset. The application uses the new editable vector artwork in `assets/brand/` and `assets/icons/`. Login carousel scenes were generated using the built-in image generation tool; their prompts and saved paths are documented in [assets/IMAGE_PROMPTS.md](assets/IMAGE_PROMPTS.md).
