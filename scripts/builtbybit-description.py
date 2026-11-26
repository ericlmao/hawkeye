#!/usr/bin/env python3
"""Converts MODRINTH.md into the BBCode description for BuiltByBit.

Usage: scripts/builtbybit-description.py [MODRINTH.md] [BUILTBYBIT.bbcode]

BuiltByBit runs XenForo, whose BBCode turns every newline into a line break, so wrapped Markdown
paragraphs and list items are joined into single lines. A blank line shows as an empty line, so one is
written only between two text paragraphs and before a heading; lists, tables, code and spoilers follow
on the next line because they bring their own margins. Shields.io badges are left out (they link to
other stores), <details> blocks become spoilers and ## / ### headings become HEADING=1 / HEADING=2.
Only the Markdown that MODRINTH.md uses is handled.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def inline(text: str) -> str:
    codes: list[str] = []

    def keep(match: re.Match) -> str:
        codes.append(f"[ICODE]{match.group(1)}[/ICODE]")
        return f"\0{len(codes) - 1}\0"

    text = re.sub(r"`([^`]+)`", keep, text)
    text = re.sub(r"!\[[^\]]*\]\(([^)]+)\)", r"[IMG]\1[/IMG]", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"[URL=\2]\1[/URL]", text)
    text = re.sub(r"\*\*(.+?)\*\*", r"[B]\1[/B]", text)
    return re.sub(r"\0(\d+)\0", lambda m: codes[int(m.group(1))], text)


def cells(row: str) -> list[str]:
    return [inline(c.strip()) for c in row.strip().strip("|").split("|")]


def convert(markdown: str) -> str:
    lines = markdown.splitlines()
    out: list[str] = []
    i = 0

    previous = ""

    def block(text: str, kind: str) -> None:
        # A blank line shows as an empty line. Headings, lists, tables, code and spoilers bring their
        # own margins, so only text gets one: between two paragraphs and before a new section.
        nonlocal previous
        if (kind == "text" and previous == "text") or (kind == "heading" and previous not in ("", "heading")):
            out.append("")
        out.append(text)
        previous = kind

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if not stripped or stripped == "<details>":
            i += 1
        elif stripped.startswith("[!["):
            i += 1  # badge
        elif m := re.fullmatch(r"<summary>(.*)</summary>", stripped):
            block(f'[SPOILER="{m.group(1)}"]', "open")
            i += 1
        elif stripped == "</details>":
            block("[/SPOILER]", "block")
            i += 1
        elif m := re.fullmatch(r"(#{2,3}) (.*)", stripped):
            level = len(m.group(1)) - 1
            block(f"[HEADING={level}]{inline(m.group(2))}[/HEADING]", "heading")
            i += 1
        elif m := re.fullmatch(r"```(\w*)", stripped):
            i += 1
            body = []
            while lines[i].strip() != "```":
                body.append(lines[i])
                i += 1
            i += 1
            tag = f"[CODE={m.group(1)}]" if m.group(1) else "[CODE]"
            block(tag + "\n".join(body) + "[/CODE]", "block")
        elif stripped.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(lines[i])
                i += 1
            table = ["[TABLE]"]
            table.append("[TR]" + "".join(f"[TH]{c}[/TH]" for c in cells(rows[0])) + "[/TR]")
            for row in rows[2:]:
                table.append("[TR]" + "".join(f"[TD]{c}[/TD]" for c in cells(row)) + "[/TR]")
            table.append("[/TABLE]")
            block("\n".join(table), "block")
        elif stripped.startswith("- "):
            items: list[str] = []
            while i < len(lines) and (lines[i].startswith("- ") or lines[i].startswith("  ")):
                if lines[i].startswith("- "):
                    items.append(lines[i][2:].strip())
                else:
                    items[-1] += " " + lines[i].strip()
                i += 1
            block("[LIST]\n" + "\n".join(f"[*]{inline(item)}" for item in items) + "\n[/LIST]", "block")
        else:
            paragraph = []
            while i < len(lines) and lines[i].strip() and not re.match(
                r"\s*(```|\||- |#{2,3} |</?details>|<summary>)", lines[i]
            ):
                paragraph.append(lines[i].strip())
                i += 1
            block(inline(" ".join(paragraph)), "text")
    return "\n".join(out) + "\n"


def main() -> None:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "MODRINTH.md"
    target = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "BUILTBYBIT.bbcode"
    target.write_text(convert(source.read_text(encoding="utf-8")), encoding="utf-8")
    print(f"wrote {target}")


if __name__ == "__main__":
    main()
