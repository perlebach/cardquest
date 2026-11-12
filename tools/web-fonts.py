"""
Makes the fonts of the web version (src/wasmJsMain/composeResources/font).

A browser canvas has no system fonts, so the emoji and symbols the game uses must come with it. Instead of the
full fonts (~5 MB) only the characters that appear in src/commonMain are kept (a few hundred kB).
Run again after adding new emoji or symbols to the game:

    pip install fonttools
    python tools/web-fonts.py

Fonts: Noto Color Emoji (COLRv1), Noto Sans Symbols 2, Noto Sans Math – all SIL Open Font License.
"""
import pathlib, subprocess, tempfile
from fontTools import subset
from fontTools.ttLib import TTFont

EMOJI_SELECTOR = chr(0xFE0F)  # "show as emoji", after e.g. the shield in "🛡️"
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src/wasmJsMain/composeResources/font"
FONTS = {  # output name -> source
    "emoji": "https://github.com/googlefonts/noto-emoji/raw/main/2D/fonts/Noto-COLRv1-noflags.ttf",
    "symbols": "https://github.com/google/fonts/raw/main/ofl/notosanssymbols2/NotoSansSymbols2-Regular.ttf",
    "math": "https://github.com/google/fonts/raw/main/ofl/notosansmath/NotoSansMath-Regular.ttf",
}

used = sorted({c for f in (ROOT / "src/commonMain").rglob("*.kt") for c in f.read_text(encoding="utf-8") if ord(c) > 0x2000})
todo = set(used)
OUT.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    for name, url in FONTS.items():
        src = pathlib.Path(tmp) / f"{name}.ttf"
        subprocess.run(["curl", "-sSfL", "-o", str(src), url], check=True)  # curl: uses the Windows certificate store
        cmap = TTFont(src).getBestCmap()
        chars = [c for c in todo if ord(c) in cmap]  # each character from the first font that has it
        todo -= set(chars)
        if name == "emoji":
            chars.append(EMOJI_SELECTOR)
        options = subset.Options()
        options.layout_features = ["*"]
        font = TTFont(src)
        s = subset.Subsetter(options)
        s.populate(text="".join(chars))
        s.subset(font)
        font.save(OUT / f"{name}.ttf")
        print(f"{name}.ttf: {len(chars)} characters, {(OUT / f'{name}.ttf').stat().st_size // 1024} kB")

missing = [f"{c} U+{ord(c):04X}" for c in sorted(todo) if c != EMOJI_SELECTOR]
print("not in any font (the default font may still have them):", ", ".join(missing) or "none")
