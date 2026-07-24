#!/usr/bin/env python3
"""Generate the maimai Q player manual PDF from its Markdown source."""

from __future__ import annotations

import argparse
import html
import os
import re
import unicodedata
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    CondPageBreak,
    HRFlowable,
    ListFlowable,
    ListItem,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


PAGE_WIDTH, PAGE_HEIGHT = A4
CONTENT_WIDTH = PAGE_WIDTH - 36 * mm

PRIMARY = colors.HexColor("#1677FF")
PRIMARY_DARK = colors.HexColor("#0958D9")
TEXT = colors.HexColor("#202124")
SECONDARY = colors.HexColor("#5F6673")
TERTIARY = colors.HexColor("#8A9099")
SEPARATOR = colors.HexColor("#DDE1E7")
SOFT_BLUE = colors.HexColor("#EEF5FF")
SOFT_GRAY = colors.HexColor("#F5F7FA")
WHITE = colors.white


def find_font_pair() -> tuple[Path, Path]:
    custom_regular = os.environ.get("MAIMAI_Q_PDF_FONT")
    custom_bold = os.environ.get("MAIMAI_Q_PDF_BOLD_FONT")
    candidates = []
    if custom_regular:
        candidates.append((Path(custom_regular), Path(custom_bold or custom_regular)))
    candidates.extend(
        [
            (Path(r"C:\Windows\Fonts\Deng.ttf"), Path(r"C:\Windows\Fonts\Dengb.ttf")),
            (
                Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"),
            ),
            (
                Path("/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf"),
                Path("/usr/share/fonts/truetype/noto/NotoSansSC-Bold.ttf"),
            ),
        ]
    )
    for regular, bold in candidates:
        if regular.is_file() and bold.is_file():
            return regular, bold
    raise FileNotFoundError(
        "No supported Chinese font was found. Set MAIMAI_Q_PDF_FONT and "
        "MAIMAI_Q_PDF_BOLD_FONT to suitable TTF files."
    )


def register_fonts() -> None:
    regular, bold = find_font_pair()
    pdfmetrics.registerFont(TTFont("ManualSans", str(regular)))
    pdfmetrics.registerFont(TTFont("ManualSansBold", str(bold)))
    pdfmetrics.registerFontFamily(
        "ManualSans",
        normal="ManualSans",
        bold="ManualSansBold",
        italic="ManualSans",
        boldItalic="ManualSansBold",
    )


def inline_markup(text: str) -> str:
    placeholders: list[str] = []

    def hold(value: str) -> str:
        placeholders.append(value)
        return f"@@INLINE{len(placeholders) - 1}@@"

    def code_replacement(match: re.Match[str]) -> str:
        value = html.escape(match.group(1))
        return hold(f'<font name="ManualSans" color="#0958D9">{value}</font>')

    def link_replacement(match: re.Match[str]) -> str:
        label = html.escape(match.group(1))
        target = match.group(2)
        if target.startswith("#"):
            return hold(f'<font color="#0958D9">{label}</font>')
        safe_target = html.escape(target, quote=True)
        return hold(f'<link href="{safe_target}" color="#0958D9">{label}</link>')

    text = re.sub(r"`([^`]+)`", code_replacement, text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link_replacement, text)
    escaped = html.escape(text)
    escaped = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", escaped)
    for index, value in enumerate(placeholders):
        escaped = escaped.replace(f"@@INLINE{index}@@", value)
    return escaped


def display_width(value: str) -> int:
    return sum(2 if unicodedata.east_asian_width(char) in {"W", "F", "A"} else 1 for char in value)


def parse_table(lines: list[str], styles: dict[str, ParagraphStyle]) -> Table:
    rows = [[cell.strip() for cell in line.strip().strip("|").split("|")] for line in lines]
    if len(rows) > 1 and all(re.fullmatch(r":?-{3,}:?", cell.replace(" ", "")) for cell in rows[1]):
        rows.pop(1)
    column_count = max(len(row) for row in rows)
    normalized = [row + [""] * (column_count - len(row)) for row in rows]
    weights = []
    for column in range(column_count):
        width = max(display_width(row[column]) for row in normalized)
        weights.append(max(8, min(width, 34)))
    total = sum(weights)
    column_widths = [CONTENT_WIDTH * weight / total for weight in weights]
    data = []
    for row_index, row in enumerate(normalized):
        style = styles["table_header"] if row_index == 0 else styles["table_cell"]
        data.append([Paragraph(inline_markup(cell), style) for cell in row])
    table = Table(data, colWidths=column_widths, repeatRows=1, hAlign="LEFT", splitByRow=1)
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), SOFT_BLUE),
                ("TEXTCOLOR", (0, 0), (-1, 0), PRIMARY_DARK),
                ("BACKGROUND", (0, 1), (-1, -1), WHITE),
                ("GRID", (0, 0), (-1, -1), 0.55, SEPARATOR),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 7),
                ("RIGHTPADDING", (0, 0), (-1, -1), 7),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ]
        )
    )
    return table


def build_styles() -> dict[str, ParagraphStyle]:
    sample = getSampleStyleSheet()
    styles = {
        "cover_brand": ParagraphStyle(
            "CoverBrand",
            parent=sample["Normal"],
            fontName="ManualSansBold",
            fontSize=16,
            leading=22,
            textColor=PRIMARY,
            alignment=TA_LEFT,
            spaceAfter=10,
        ),
        "cover_title": ParagraphStyle(
            "CoverTitle",
            parent=sample["Title"],
            fontName="ManualSansBold",
            fontSize=32,
            leading=42,
            textColor=TEXT,
            alignment=TA_LEFT,
            spaceAfter=15,
        ),
        "cover_intro": ParagraphStyle(
            "CoverIntro",
            parent=sample["Normal"],
            fontName="ManualSans",
            fontSize=11,
            leading=19,
            textColor=SECONDARY,
            alignment=TA_LEFT,
            spaceAfter=10,
        ),
        "cover_version": ParagraphStyle(
            "CoverVersion",
            parent=sample["Normal"],
            fontName="ManualSansBold",
            fontSize=10,
            leading=16,
            textColor=WHITE,
            alignment=TA_CENTER,
        ),
        "h2": ParagraphStyle(
            "H2",
            parent=sample["Heading1"],
            fontName="ManualSansBold",
            fontSize=20,
            leading=28,
            textColor=TEXT,
            spaceBefore=0,
            spaceAfter=13,
            keepWithNext=True,
        ),
        "h3": ParagraphStyle(
            "H3",
            parent=sample["Heading2"],
            fontName="ManualSansBold",
            fontSize=13.5,
            leading=21,
            textColor=PRIMARY_DARK,
            spaceBefore=13,
            spaceAfter=6,
            keepWithNext=True,
        ),
        "h4": ParagraphStyle(
            "H4",
            parent=sample["Heading3"],
            fontName="ManualSansBold",
            fontSize=10.5,
            leading=17,
            textColor=TEXT,
            spaceBefore=9,
            spaceAfter=4,
            keepWithNext=True,
        ),
        "body": ParagraphStyle(
            "Body",
            parent=sample["BodyText"],
            fontName="ManualSans",
            fontSize=9.4,
            leading=16.2,
            textColor=TEXT,
            spaceAfter=7,
            allowWidows=0,
            allowOrphans=0,
        ),
        "list": ParagraphStyle(
            "List",
            parent=sample["BodyText"],
            fontName="ManualSans",
            fontSize=9.2,
            leading=15.5,
            textColor=TEXT,
            spaceAfter=2,
        ),
        "quote": ParagraphStyle(
            "Quote",
            parent=sample["BodyText"],
            fontName="ManualSans",
            fontSize=9.2,
            leading=16,
            textColor=SECONDARY,
            backColor=SOFT_BLUE,
            borderColor=PRIMARY,
            borderWidth=0,
            borderPadding=(8, 10, 8, 12),
            leftIndent=0,
            rightIndent=0,
            spaceBefore=4,
            spaceAfter=9,
        ),
        "table_header": ParagraphStyle(
            "TableHeader",
            parent=sample["Normal"],
            fontName="ManualSansBold",
            fontSize=8.4,
            leading=12.5,
            textColor=PRIMARY_DARK,
        ),
        "table_cell": ParagraphStyle(
            "TableCell",
            parent=sample["Normal"],
            fontName="ManualSans",
            fontSize=8.2,
            leading=12.8,
            textColor=TEXT,
        ),
    }
    styles["body_lead"] = ParagraphStyle(
        "BodyLead",
        parent=styles["body"],
        keepWithNext=True,
    )
    return styles


class ManualDocTemplate(SimpleDocTemplate):
    def afterFlowable(self, flowable) -> None:  # noqa: N802 - ReportLab API
        bookmark = getattr(flowable, "manual_bookmark", None)
        title = getattr(flowable, "manual_title", None)
        if bookmark and title:
            self.canv.bookmarkPage(bookmark)
            self.canv.addOutlineEntry(title, bookmark, level=0, closed=False)


def draw_cover(canvas, doc) -> None:
    canvas.saveState()
    canvas.setTitle("maimai Q 玩家使用手册")
    canvas.setAuthor("maimai Q")
    canvas.setFillColor(SOFT_GRAY)
    canvas.rect(0, 0, PAGE_WIDTH, PAGE_HEIGHT, fill=1, stroke=0)
    canvas.setFillColor(PRIMARY)
    canvas.rect(0, 0, 10 * mm, PAGE_HEIGHT, fill=1, stroke=0)
    canvas.setStrokeColor(colors.HexColor("#B9D6FF"))
    canvas.setLineWidth(1)
    canvas.line(28 * mm, 34 * mm, PAGE_WIDTH - 24 * mm, 34 * mm)
    canvas.restoreState()


def draw_body_page(canvas, doc) -> None:
    canvas.saveState()
    body_page = canvas.getPageNumber() - 1
    canvas.setFont("ManualSansBold", 8.5)
    canvas.setFillColor(SECONDARY)
    canvas.drawString(18 * mm, PAGE_HEIGHT - 13 * mm, "maimai Q")
    canvas.setFont("ManualSans", 8.5)
    canvas.setFillColor(TERTIARY)
    canvas.drawRightString(PAGE_WIDTH - 18 * mm, PAGE_HEIGHT - 13 * mm, "玩家使用手册")
    canvas.setStrokeColor(SEPARATOR)
    canvas.setLineWidth(0.6)
    canvas.line(18 * mm, PAGE_HEIGHT - 16 * mm, PAGE_WIDTH - 18 * mm, PAGE_HEIGHT - 16 * mm)
    canvas.line(18 * mm, 15 * mm, PAGE_WIDTH - 18 * mm, 15 * mm)
    canvas.setFont("ManualSans", 8)
    canvas.setFillColor(TERTIARY)
    canvas.drawString(18 * mm, 9.5 * mm, f"版本 {doc.manual_version}")
    canvas.drawRightString(PAGE_WIDTH - 18 * mm, 9.5 * mm, str(body_page))
    canvas.restoreState()


def parse_body(lines: list[str], styles: dict[str, ParagraphStyle]) -> list:
    story = []
    index = next(i for i, line in enumerate(lines) if line.startswith("## "))
    section_index = 0
    first_section = True
    while index < len(lines):
        raw = lines[index].rstrip()
        if not raw:
            index += 1
            continue
        heading = re.match(r"^(#{2,4})\s+(.+)$", raw)
        if heading:
            level = len(heading.group(1))
            title = heading.group(2).strip()
            if level == 2:
                if not first_section:
                    story.append(CondPageBreak(80 * mm))
                    story.append(Spacer(1, 5))
                first_section = False
                section_index += 1
                paragraph = Paragraph(inline_markup(title), styles["h2"])
                paragraph.manual_bookmark = f"section-{section_index}"
                paragraph.manual_title = title
                story.extend(
                    [
                        HRFlowable(
                            width=13 * mm,
                            thickness=2.4,
                            color=PRIMARY,
                            spaceBefore=0,
                            spaceAfter=8,
                            hAlign="LEFT",
                        ),
                        paragraph,
                    ]
                )
            elif level == 3:
                story.append(Paragraph(inline_markup(title), styles["h3"]))
            else:
                story.append(Paragraph(inline_markup(title), styles["h4"]))
            index += 1
            continue
        if raw.startswith("|"):
            table_lines = []
            while index < len(lines) and lines[index].lstrip().startswith("|"):
                table_lines.append(lines[index].rstrip())
                index += 1
            story.extend([parse_table(table_lines, styles), Spacer(1, 8)])
            continue
        if raw.startswith(">"):
            quote_lines = []
            while index < len(lines) and lines[index].lstrip().startswith(">"):
                quote_lines.append(lines[index].lstrip()[1:].strip())
                index += 1
            story.append(Paragraph(inline_markup(" ".join(quote_lines)), styles["quote"]))
            continue
        if re.match(r"^-\s+", raw):
            items = []
            while index < len(lines) and re.match(r"^-\s+", lines[index].rstrip()):
                value = re.sub(r"^-\s+", "", lines[index].rstrip())
                items.append(ListItem(Paragraph(inline_markup(value), styles["list"]), leftIndent=8))
                index += 1
            story.append(
                ListFlowable(
                    items,
                    bulletType="bullet",
                    start="circle",
                    leftIndent=17,
                    bulletFontName="ManualSans",
                    bulletFontSize=7,
                    bulletColor=PRIMARY,
                    spaceAfter=7,
                )
            )
            continue
        if re.match(r"^\d+\.\s+", raw):
            items = []
            while index < len(lines) and re.match(r"^\d+\.\s+", lines[index].rstrip()):
                value = re.sub(r"^\d+\.\s+", "", lines[index].rstrip())
                items.append(ListItem(Paragraph(inline_markup(value), styles["list"]), leftIndent=8))
                index += 1
            story.append(
                ListFlowable(
                    items,
                    bulletType="1",
                    start="1",
                    leftIndent=20,
                    bulletFontName="ManualSansBold",
                    bulletFontSize=8.5,
                    bulletColor=PRIMARY_DARK,
                    spaceAfter=7,
                )
            )
            continue
        paragraph_lines = [raw.strip()]
        index += 1
        while index < len(lines):
            candidate = lines[index].rstrip()
            if (
                not candidate
                or re.match(r"^#{2,4}\s+", candidate)
                or candidate.startswith("|")
                or candidate.startswith(">")
                or re.match(r"^-\s+", candidate)
                or re.match(r"^\d+\.\s+", candidate)
            ):
                break
            paragraph_lines.append(candidate.strip())
            index += 1
        paragraph_text = " ".join(paragraph_lines)
        style = styles["body_lead"] if paragraph_text.endswith(("：", ":")) else styles["body"]
        story.append(Paragraph(inline_markup(paragraph_text), style))
    return story


def build_pdf(source: Path, output: Path) -> None:
    register_fonts()
    lines = source.read_text(encoding="utf-8").splitlines()
    title = lines[0].lstrip("# ").strip()
    first_section = next(i for i, line in enumerate(lines) if line.startswith("## "))
    intro = [line.strip() for line in lines[1:first_section] if line.strip()]
    version_match = re.search(r"`(\d+\.\d+\.\d+)`", " ".join(intro))
    version = version_match.group(1) if version_match else "unknown"
    styles = build_styles()

    output.parent.mkdir(parents=True, exist_ok=True)
    doc = ManualDocTemplate(
        str(output),
        pagesize=A4,
        rightMargin=18 * mm,
        leftMargin=18 * mm,
        topMargin=22 * mm,
        bottomMargin=20 * mm,
        title=title,
        author="maimai Q",
        subject="面向机厅玩家的 maimai Q 排队终端使用说明",
    )
    doc.manual_version = version
    version_badge = Table(
        [[Paragraph(f"版本 {html.escape(version)}", styles["cover_version"])]],
        colWidths=[38 * mm],
        rowHeights=[10 * mm],
        hAlign="LEFT",
    )
    version_badge.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), PRIMARY),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("LEFTPADDING", (0, 0), (-1, -1), 8),
                ("RIGHTPADDING", (0, 0), (-1, -1), 8),
            ]
        )
    )
    cover_intro = intro[0] if intro else ""
    cover_notice = intro[1] if len(intro) > 1 else ""
    story = [
        Spacer(1, 43 * mm),
        Paragraph("maimai Q", styles["cover_brand"]),
        Paragraph(html.escape(title.replace("maimai Q ", "")), styles["cover_title"]),
        version_badge,
        Spacer(1, 15 * mm),
        Paragraph(inline_markup(cover_intro), styles["cover_intro"]),
        Paragraph(inline_markup(cover_notice), styles["cover_intro"]),
        Spacer(1, 1),
        PageBreak(),
    ]
    story.extend(parse_body(lines, styles))
    doc.build(story, onFirstPage=draw_cover, onLaterPages=draw_body_page)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("docs/user-manual.md"))
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("output/pdf/maimai-Q-玩家使用手册.pdf"),
    )
    args = parser.parse_args()
    build_pdf(args.input.resolve(), args.output.resolve())
    print(args.output.resolve())


if __name__ == "__main__":
    main()
