"""Confined local folder creation for JARVIS desktop workspaces.

Folders can only be created as direct children of the dedicated workspace-files
directory. This module never accepts arbitrary paths or shell commands.
"""
from __future__ import annotations

from pathlib import Path
import re


MAX_FOLDER_NAME_CHARS = 80
_INVALID_WINDOWS_CHARS = frozenset('<>:"/\\|?*')
_RESERVED_WINDOWS_NAMES = frozenset({
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{index}" for index in range(1, 10)),
    *(f"LPT{index}" for index in range(1, 10)),
})
_CONTROL = re.compile(r"[\x00-\x1f\x7f]")


class WorkspaceFolderError(ValueError):
    """Invalid folder name, conflicting target, or unavailable workspace root."""


def validate_folder_name(value: object) -> str:
    """Validate a single directory name on both Windows and POSIX."""
    if not isinstance(value, str):
        raise WorkspaceFolderError("Имя папки должно быть текстом.")
    name = value.strip()
    if not name or len(name) > MAX_FOLDER_NAME_CHARS:
        raise WorkspaceFolderError(
            f"Имя папки должно содержать от 1 до {MAX_FOLDER_NAME_CHARS} символов."
        )
    if name in {".", ".."} or name.startswith("."):
        raise WorkspaceFolderError("Скрытые и относительные имена папок запрещены.")
    if _CONTROL.search(name) or any(char in _INVALID_WINDOWS_CHARS for char in name):
        raise WorkspaceFolderError("В имени папки есть недопустимые символы.")
    if name.endswith((".", " ")):
        raise WorkspaceFolderError("Имя папки не может заканчиваться точкой или пробелом.")
    device_stem = name.split(".", 1)[0].upper()
    if device_stem in _RESERVED_WINDOWS_NAMES:
        raise WorkspaceFolderError("Это зарезервированное системное имя Windows.")
    return name


def create_workspace_folder(root: str | Path, value: object) -> dict[str, object]:
    """Create one new child directory beneath a fixed application-owned root."""
    name = validate_folder_name(value)
    root_path = Path(root).expanduser()
    try:
        root_path.mkdir(mode=0o700, parents=True, exist_ok=True)
        if root_path.is_symlink():
            raise WorkspaceFolderError("Каталог workspace-files не может быть символической ссылкой.")
        resolved_root = root_path.resolve(strict=True)
        if not resolved_root.is_dir():
            raise WorkspaceFolderError("Папка рабочих файлов JARVIS недоступна.")
        expected_root = root_path.parent.resolve(strict=True) / root_path.name
        if resolved_root != expected_root:
            raise WorkspaceFolderError("Каталог workspace-files должен находиться внутри данных JARVIS.")
        target = resolved_root / name
        # The name validator forbids separators; this is a second confinement check.
        if target.parent != resolved_root:
            raise WorkspaceFolderError("Путь папки выходит за пределы workspace JARVIS.")
        target.mkdir(mode=0o700, exist_ok=False)
    except FileExistsError as exc:
        raise WorkspaceFolderError("Папка с таким именем уже существует.") from exc
    except WorkspaceFolderError:
        raise
    except OSError as exc:
        raise WorkspaceFolderError("Не удалось создать папку в workspace JARVIS.") from exc
    return {
        "created": True,
        "name": name,
        "relative_path": f"workspace-files/{name}",
    }
