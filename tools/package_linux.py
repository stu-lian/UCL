import tarfile
from pathlib import Path

root = Path("build/linux/Union Craft Launcher")
out = Path("build/dist/UnionCraftLauncher-linux-x64.tar.gz")
out.parent.mkdir(parents=True, exist_ok=True)


def archive_name(path: Path) -> str:
    return str(Path("Union Craft Launcher") / path.relative_to(root))


with tarfile.open(out, "w:gz", format=tarfile.PAX_FORMAT) as archive:
    for path in sorted(root.rglob("*")):
        info = archive.gettarinfo(str(path), arcname=archive_name(path))
        if path.name == "start.sh":
            info.mode = 0o755
        if path.is_file():
            with path.open("rb") as handle:
                archive.addfile(info, handle)
        else:
            archive.addfile(info)

print(f"Linux package -> {out} ({out.stat().st_size / 1024 / 1024:.1f} MB)")
