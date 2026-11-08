"""
Выпуск новой версии WakeyWakey.

    python release.py 1.1.0

1. Ставит versionName = 1.1.0 и versionCode + 1 в app/build.gradle.kts.
2. Проверяет, что в CHANGELOG.md есть раздел «## 1.1.0».
3. Собирает подписанный release-APK → dist/WakeyWakey-1.1.0.apk.
4. Коммит, тег v1.1.0, push, GitHub-релиз с APK и текстом из CHANGELOG.
   Приложение на телефоне увидит этот релиз через «Проверить обновления».
"""
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
GRADLE = ROOT / "app" / "build.gradle.kts"
REPO = "erronsivors/WakeyWakey"


def run(*cmd: str) -> None:
    print("›", " ".join(cmd))
    subprocess.run(cmd, cwd=ROOT, check=True)


def notes(version: str) -> str:
    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    m = re.search(rf"^## {re.escape(version)}\b.*?$(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not m:
        sys.exit(f"В CHANGELOG.md нет раздела «## {version}» — сначала опишите изменения")
    return m.group(1).strip()


def main() -> None:
    if len(sys.argv) != 2 or not re.fullmatch(r"\d+\.\d+\.\d+", sys.argv[1]):
        sys.exit("Использование: python release.py 1.2.3")
    version = sys.argv[1]
    body = notes(version)
    if not (ROOT / "keystore.properties").exists():
        sys.exit("Нет keystore.properties — без ключа обновление не встанет поверх старой версии")

    g = GRADLE.read_text(encoding="utf-8")
    code = int(re.search(r"versionCode = (\d+)", g).group(1))
    old = re.search(r'versionName = "([^"]+)"', g).group(1)
    if old != version:
        code += 1
        g = re.sub(r"versionCode = \d+", f"versionCode = {code}", g)
        g = re.sub(r'versionName = "[^"]+"', f'versionName = "{version}"', g)
        GRADLE.write_text(g, encoding="utf-8")
    print(f"Версия {version} (versionCode {code})")

    gradlew = str(ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
    run(gradlew, "assembleRelease", "-q", "--console=plain")
    apk = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    out = dist / f"WakeyWakey-{version}.apk"
    shutil.copy2(apk, out)

    run("git", "add", "-A")
    if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode != 0:
        run("git", "commit", "-m", f"WakeyWakey {version}")
    run("git", "tag", f"v{version}")
    run("git", "push", "origin", "HEAD", "--tags")
    run("gh", "release", "create", f"v{version}", str(out), "--repo", REPO,
        "--title", f"WakeyWakey {version}", "--notes", body)
    print(f"Готово: https://github.com/{REPO}/releases/tag/v{version}")


if __name__ == "__main__":
    main()
