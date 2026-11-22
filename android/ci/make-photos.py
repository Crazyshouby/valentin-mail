# Fabrique le message d'essai de la galerie : une photo qui porte sa miniature (comme celles d'un
# appareil photo), couchée et à redresser, et un petit dessin. La photo est bleue, sa miniature rouge :
# le test sait ainsi laquelle des deux l'appli a utilisée.
import base64, io, struct, sys
from PIL import Image, ImageDraw

def jpeg(size, color, quality=85):
    im = Image.new("RGB", size, color)
    d = ImageDraw.Draw(im)
    w, h = size
    d.rectangle([w // 8, h // 8, w // 4, h // 4], fill=tuple(min(255, c + 40) for c in color))
    out = io.BytesIO()
    im.save(out, "JPEG", quality=quality)
    return out.getvalue()

main = jpeg((640, 480), (20, 60, 200))
thumb = jpeg((160, 120), (210, 30, 30), 80)

# En-tête TIFF, IFD0 (orientation 6 : à tourner d'un quart de tour), IFD1 (emplacement de la miniature).
tiff = b"MM" + struct.pack(">HI", 42, 8)
tiff += struct.pack(">H", 1) + struct.pack(">HHIHH", 0x0112, 3, 1, 6, 0) + struct.pack(">I", 26)
at = 26 + 2 + 3 * 12 + 4
tiff += struct.pack(">H", 3)
tiff += struct.pack(">HHIHH", 0x0103, 3, 1, 6, 0)
tiff += struct.pack(">HHII", 0x0201, 4, 1, at)
tiff += struct.pack(">HHII", 0x0202, 4, 1, len(thumb))
tiff += struct.pack(">I", 0)
assert len(tiff) == at
payload = b"Exif\x00\x00" + tiff + thumb
app1 = b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload
photo = main[:2] + app1 + main[2:]

check = Image.open(io.BytesIO(photo))
assert check.size == (640, 480) and check.getexif().get(0x0112) == 6, "photo illisible"

png = io.BytesIO()
Image.new("RGB", (64, 64), (30, 170, 70)).save(png, "PNG")
png = png.getvalue()

def b64(data):
    text = base64.b64encode(data).decode()
    return "\r\n".join(text[i:i + 76] for i in range(0, len(text), 76))

lines = [
    'From: "Chantier Dubois" <chantier@example.net>',
    "To: valentin@valentinlarge.com",
    "Subject: Photos du chantier",
    "Date: Sun, 22 Nov 2026 09:30:00 -0500",
    "Message-ID: <photos-042@example.net>",
    "MIME-Version: 1.0",
    'Content-Type: multipart/mixed; boundary="PHOTOS"',
    "",
    "--PHOTOS",
    "Content-Type: text/plain; charset=UTF-8",
    "",
    "Bonjour, voici deux images du chantier.",
    "--PHOTOS",
    'Content-Type: image/jpeg; name="photo.jpg"',
    'Content-Disposition: attachment; filename="photo.jpg"',
    "Content-Transfer-Encoding: base64",
    "",
    b64(photo),
    "--PHOTOS",
    'Content-Type: image/png; name="dessin.png"',
    'Content-Disposition: attachment; filename="dessin.png"',
    "Content-Transfer-Encoding: base64",
    "",
    b64(png),
    "--PHOTOS--",
    "",
]
open(sys.argv[1], "wb").write("\r\n".join(lines).encode())
print("photo", len(photo), "dont miniature", len(thumb), "| dessin", len(png), "| app1 finit à l'octet", 2 + len(app1))
