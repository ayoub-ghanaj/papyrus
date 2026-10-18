#!/usr/bin/env python3
"""Build native installers and a portable runtime bundle using the host JDK 25."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION = ET.parse(ROOT / "pom.xml").getroot().find("m:version", NS).text


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--portable-only", action="store_true")
    options = parser.parse_args()
    system = platform.system()
    if system not in ("Windows", "Linux"):
        parser.error("Supported packaging hosts: Windows and Linux")
    if platform.machine().lower() not in ("amd64", "x86_64"):
        parser.error("This release targets x64 processors")
    executable = ".exe" if system == "Windows" else ""
    java_home = os.environ.get("JAVA_HOME")
    jpackage = Path(java_home) / "bin" / ("jpackage" + executable) if java_home else shutil.which("jpackage")
    jlink = Path(java_home) / "bin" / ("jlink" + executable) if java_home else shutil.which("jlink")
    if not jpackage or not jlink:
        parser.error("Set JAVA_HOME to a JDK 25 installation")
    if not options.skip_build:
        if system == "Windows":
            run("cmd.exe", "/c", "mvnw.cmd", "-B", "-ntp", "clean", "verify")
        else:
            run("sh", "mvnw", "-B", "-ntp", "clean", "verify")

    staging = ROOT / "target" / "packaging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    input_dir = staging / "input"
    input_dir.mkdir()
    shutil.copy2(ROOT / "target" / f"papyrus-{VERSION}.jar", input_dir)
    shutil.copytree(ROOT / "target" / "lib", input_dir / "lib")
    shutil.copy2(ROOT / "THIRD_PARTY.md", input_dir)
    shutil.copy2(ROOT / "README.md", input_dir)
    runtime = staging / "runtime"
    run(jlink, "--add-modules", "java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata",
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6", "--output", runtime)

    images = staging / "images"
    images.mkdir()
    common = ["--name", "Papyrus", "--app-version", VERSION, "--vendor", "Ayoub Ghanaj",
              "--description", "Multilingual library manager", "--input", input_dir,
              "--main-jar", f"papyrus-{VERSION}.jar", "--main-class", "papyrus.Launcher",
              "--runtime-image", runtime, "--java-options", "--enable-native-access=ALL-UNNAMED",
              "--java-options", "--sun-misc-unsafe-memory-access=allow",
              "--icon", ROOT / "packaging" / ("papyrus.ico" if system == "Windows" else "papyrus.png")]
    run(jpackage, "--type", "app-image", "--dest", images, *common)
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    prefix = f"Papyrus-{VERSION}-{system.lower()}-x64"
    archive = output / (prefix + ("-portable.zip" if system == "Windows" else "-portable.tar.gz"))
    app_image = images / "Papyrus"
    if system == "Windows":
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
            for path in app_image.rglob("*"):
                if path.is_file():
                    bundle.write(path, path.relative_to(images))
    else:
        with tarfile.open(archive, "w:gz") as bundle:
            bundle.add(app_image, arcname="Papyrus")

    produced = [archive]
    if not options.portable_only:
        installer = staging / "installer"
        installer.mkdir()
        if system == "Windows":
            # Stable upgrade UUID allows subsequent releases to upgrade the same app.
            run(jpackage, "--type", "exe", "--dest", installer, "--app-image", app_image,
                "--name", "Papyrus", "--app-version", VERSION, "--vendor", "Ayoub Ghanaj",
                "--win-per-user-install", "--win-dir-chooser", "--win-menu", "--win-shortcut",
                "--win-upgrade-uuid", "a4fd2f82-84cc-4fe9-b231-5de3b6f016ee")
            extension = ".exe"
        else:
            run(jpackage, "--type", "deb", "--dest", installer, "--app-image", app_image,
                "--name", "Papyrus", "--app-version", VERSION, "--vendor", "Ayoub Ghanaj",
                "--linux-package-name", "papyrus", "--linux-shortcut", "--linux-app-category", "Education",
                "--linux-deb-maintainer", "ayoub-ghanaj@users.noreply.github.com")
            extension = ".deb"
        source = next(installer.glob("*" + extension))
        destination = output / (prefix + extension)
        if destination.exists():
            destination.chmod(0o644)
            destination.unlink()
        shutil.copy2(source, destination)
        produced.append(destination)
    # Separate checksum lists avoid collisions when CI merges OS artifacts.
    sums = output / (prefix + "-SHA256SUMS.txt")
    sums.write_text("".join(f"{hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()}  {path.name}\n" for path in produced), encoding="utf-8")
    for path in produced + [sums]:
        print(path)


if __name__ == "__main__":
    main()
