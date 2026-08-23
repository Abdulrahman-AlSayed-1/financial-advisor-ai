#!/usr/bin/env python3
"""
PyMuPDF PDF -> Structured JSON & Images Extractor for Financial Advisor AI.

Extracts text blocks, tables, images, and drawing bounds from PDF with pixel-accurate
Top-Left origin coordinates. Renders page images and saves embedded/cropped media.

Usage:
    python3 scripts/pymupdf_extractor.py [--input PDF_PATH] [--output-json JSON_PATH] [--output-dir DIR_PATH]
"""

import argparse
import datetime
import json
import os
import sys
from pathlib import Path
import pymupdf


def extract_pdf(pdf_path: str, output_json_path: str, output_dir: str):
    pdf_file = Path(pdf_path)
    if not pdf_file.exists():
        print(f"Error: Input PDF '{pdf_path}' not found.", file=sys.stderr)
        sys.exit(1)

    out_dir = Path(output_dir)
    pages_dir = out_dir / "pages"
    images_dir = out_dir / "images"
    regions_dir = out_dir / "regions"

    pages_dir.mkdir(parents=True, exist_ok=True)
    images_dir.mkdir(parents=True, exist_ok=True)
    regions_dir.mkdir(parents=True, exist_ok=True)

    doc = pymupdf.open(str(pdf_file))
    total_pages = len(doc)
    print(f"Opened PDF '{pdf_path}' ({total_pages} pages)")

    raw_pages = []

    for pno in range(total_pages):
        page = doc[pno]
        page_num = pno + 1
        page_rect = page.rect
        width = page_rect.width
        height = page_rect.height

        # 1. Render page image at 150 DPI (matrix 150/72 ≈ 2.0833)
        zoom = 150.0 / 72.0
        mat = pymupdf.Matrix(zoom, zoom)
        pix = page.get_pixmap(matrix=mat)
        page_img_rel = f"pages/page_{page_num}.png"
        page_img_full = pages_dir / f"page_{page_num}.png"
        pix.save(str(page_img_full))

        # 2. Extract Text Blocks with styles and bounding boxes
        text_page_dict = page.get_text("dict")
        text_blocks = []
        block_idx = 0

        for b in text_page_dict.get("blocks", []):
            if b.get("type") == 0:  # Text block
                block_bbox = [round(c, 2) for c in b.get("bbox", [0, 0, 0, 0])]
                lines_text = []
                font_name = ""
                font_size = 0.0
                is_bold = False
                is_italic = False

                for line in b.get("lines", []):
                    line_spans = []
                    for span in line.get("spans", []):
                        span_text = span.get("text", "")
                        line_spans.append(span_text)
                        if not font_name and span_text.strip():
                            font_name = span.get("font", "")
                            font_size = round(span.get("size", 0.0), 2)
                            flags = span.get("flags", 0)
                            # flags: 2=italic, 16=bold in PyMuPDF
                            if flags & 16 or "bold" in font_name.lower():
                                is_bold = True
                            if flags & 2 or "italic" in font_name.lower() or "oblique" in font_name.lower():
                                is_italic = True
                    lines_text.append(" ".join(line_spans))

                full_block_text = "\n".join(lines_text).strip()
                if full_block_text:
                    text_blocks.append({
                        "text": full_block_text,
                        "bbox": block_bbox,
                        "font_name": font_name,
                        "font_size": font_size,
                        "is_bold": is_bold,
                        "is_italic": is_italic,
                        "block_index": block_idx
                    })
                    block_idx += 1

        # 3. Extract Tables using find_tables() with fragment clustering
        tables = []
        table_finder = page.find_tables()
        if table_finder and table_finder.tables:
            raw_tab_list = table_finder.tables
            # Group fragments by (col_count, approx x0, approx x1)
            clusters = {}
            for tab in raw_tab_list:
                bbox = tab.bbox
                key = (tab.col_count, round(bbox[0] / 35.0) * 35, round(bbox[2] / 35.0) * 35)
                clusters.setdefault(key, []).append(tab)

            t_idx = 0
            for key, tab_group in clusters.items():
                tab_group.sort(key=lambda t: t.bbox[1])
                all_rows = []
                all_cells = []
                b_x0, b_y0, b_x1, b_y1 = tab_group[0].bbox

                for t in tab_group:
                    grid = t.extract()
                    if grid:
                        all_rows.extend(grid)
                    if t.cells:
                        for cell in t.cells:
                            if cell is not None:
                                all_cells.append([round(c, 2) for c in cell])
                    b_x0 = min(b_x0, t.bbox[0])
                    b_y0 = min(b_y0, t.bbox[1])
                    b_x1 = max(b_x1, t.bbox[2])
                    b_y1 = max(b_y1, t.bbox[3])

                # Build Markdown representation
                md_lines = []
                if all_rows:
                    header_row = all_rows[0]
                    # If first row looks like data (e.g. contains numbers/funds), create generic col headers
                    md_lines.append("| " + " | ".join(str(c or "").replace("|", "/") for c in header_row) + " |")
                    md_lines.append("| " + " | ".join("---" for _ in header_row) + " |")
                    for r in all_rows[1:]:
                        md_lines.append("| " + " | ".join(str(c or "").replace("|", "/") for c in r) + " |")

                markdown_text = "\n".join(md_lines)

                tables.append({
                    "table_index": t_idx,
                    "bbox": [round(b_x0, 2), round(b_y0, 2), round(b_x1, 2), round(b_y1, 2)],
                    "row_count": len(all_rows),
                    "col_count": key[0],
                    "raw_grid": all_rows,
                    "markdown": markdown_text,
                    "cell_bboxes": all_cells
                })
                t_idx += 1

        # 4. Extract Embedded Images
        images = []
        image_list = page.get_images(full=True)
        for img_idx, img_info in enumerate(image_list):
            xref = img_info[0]
            try:
                img_bbox = [0.0, 0.0, 0.0, 0.0]
                # Try to get image bbox on page
                rects = page.get_image_rects(xref)
                if rects:
                    img_bbox = [round(c, 2) for c in rects[0]]

                base_img = doc.extract_image(xref)
                image_bytes = base_img["image"]
                img_ext = base_img["ext"]
                img_filename = f"page_{page_num}_img_{img_idx}_{xref}.{img_ext}"
                img_path_full = images_dir / img_filename
                with open(img_path_full, "wb") as f:
                    f.write(image_bytes)

                images.append({
                    "image_index": img_idx,
                    "bbox": img_bbox,
                    "width_px": base_img.get("width", 0),
                    "height_px": base_img.get("height", 0),
                    "image_path": f"images/{img_filename}",
                    "xref": xref
                })
            except Exception as e:
                print(f"Warning: Failed to extract image xref {xref} on page {page_num}: {e}", file=sys.stderr)

        # 5. Extract Vector Drawings
        drawings = []
        try:
            drawings_list = page.get_drawings()
            if drawings_list:
                for d_idx, d in enumerate(drawings_list):
                    rect = d.get("rect")
                    if rect and rect.width > 5 and rect.height > 5:
                        d_bbox = [round(rect.x0, 2), round(rect.y0, 2), round(rect.x1, 2), round(rect.y1, 2)]
                        drawings.append({
                            "drawing_index": d_idx,
                            "bbox": d_bbox,
                            "rect_count": len(d.get("items", [])),
                            "line_count": sum(1 for item in d.get("items", []) if item[0] == "l"),
                            "has_fills": d.get("fill") is not None
                        })
        except Exception as e:
            print(f"Warning: Failed to extract drawings on page {page_num}: {e}", file=sys.stderr)

        raw_pages.append({
            "page_number": page_num,
            "width": round(width, 2),
            "height": round(height, 2),
            "text_blocks": text_blocks,
            "tables": tables,
            "images": images,
            "drawings": drawings,
            "page_image_path": page_img_rel
        })

    result = {
        "source_file": pdf_file.name,
        "extraction_timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "total_pages": total_pages,
        "pages": raw_pages
    }

    out_json_path = Path(output_json_path)
    out_json_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)

    print(f"Successfully extracted {total_pages} pages -> {output_json_path}")
    print(f"Media artifacts saved under {output_dir}")


def main():
    parser = argparse.ArgumentParser(description="Extract PDF structure with PyMuPDF")
    parser.add_argument("--input", default="src/main/resources/docs/article_thebeatoct2024.pdf", help="Path to input PDF")
    parser.add_argument("--output-json", default="src/main/resources/docs/pymupdf_extraction.json", help="Path to output JSON")
    parser.add_argument("--output-dir", default="src/main/resources/docs/extraction_output", help="Directory for images")
    args = parser.parse_args()

    extract_pdf(args.input, args.output_json, args.output_dir)


if __name__ == "__main__":
    main()
