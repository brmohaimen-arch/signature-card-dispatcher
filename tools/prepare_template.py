"""
Builds templates/BM-AC008-2026/template.png from the bank's scanned PDF.

    python tools/prepare_template.py <card.pdf> [--account-boxes 13|14]

Steps (all cosmetic, none change the form's design):
  1. render the PDF at 300 DPI and turn it upright (the scan is stored sideways)
  2. remove the scanner's grey line along the left edge
  3. snap near-white paper noise to pure white
  4. clear scan specks inside the four signature boxes
  5. --account-boxes 14: the printed form has 13 digit boxes but the account number
     is 14 digits, so the 13 boxes are replaced by 14 in the same space and style
     (only if the bank approves; see README)
Requires: pymupdf, pillow, numpy.
"""
import argparse
import sys

import numpy as np
import pymupdf
from PIL import Image, ImageDraw

DPI = 300
LEFT_MARGIN_CLEAR = 110          # scanner line sits at x 16-35; first content at x 146
SIGNATURE_BOXES = [(152, 1052, 547, 376), (920, 1049, 546, 377),
                   (153, 1626, 547, 377), (921, 1626, 547, 376)]
ACCOUNT_ROW = (560, 1415, 665, 737)   # x0, x1, y0, y1 of the printed 13-box row
OUTLINE = (208, 208, 208)


def account_boxes(n):
    x0, x1, y0, y1 = ACCOUNT_ROW
    if n == 13:
        return [(x0 + round(i * 66.25), y0, 60, y1 - y0) for i in range(13)]
    w = 55
    pitch = (x1 - x0 - w) / (n - 1)
    return [(x0 + round(i * pitch), y0, w, y1 - y0) for i in range(n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("--account-boxes", type=int, choices=(13, 14), default=13)
    ap.add_argument("--out", default="templates/BM-AC008-2026/template.png")
    args = ap.parse_args()

    page = pymupdf.open(args.pdf)[0]
    pix = page.get_pixmap(dpi=DPI, alpha=False)
    img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
    img = img.transpose(Image.Transpose.ROTATE_270)          # 90 degrees clockwise
    if img.size != (1748, 2480):
        sys.exit(f"unexpected size {img.size}; expected 1748x2480 (A5 portrait at 300 DPI)")

    a = np.asarray(img).copy()
    a[:, :LEFT_MARGIN_CLEAR] = 255
    a[a.mean(axis=2) >= 250] = 255
    for x, y, w, h in SIGNATURE_BOXES:
        a[y + 8:y + h - 8, x + 8:x + w - 8] = 255
    img = Image.fromarray(a)

    if args.account_boxes == 14:
        x0, x1, y0, y1 = ACCOUNT_ROW
        draw = ImageDraw.Draw(img)
        draw.rectangle((x0 - 10, y0 - 10, x1 + 10, y1 + 10), fill=(255, 255, 255))
        for x, y, w, h in account_boxes(14):
            draw.rounded_rectangle((x, y, x + w - 1, y + h - 1), radius=10, outline=OUTLINE, width=3)

    img.save(args.out, dpi=(DPI, DPI))
    print("wrote", args.out)
    print("accountNumberBoxes:", [dict(x=x, y=y, w=w, h=h) for x, y, w, h in account_boxes(args.account_boxes)])


if __name__ == "__main__":
    main()
