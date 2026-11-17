"""Convert a pirate chase APNG into a looping Android boot ZIP."""
import argparse
from io import BytesIO
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile, ZipInfo

from PIL import Image


def convert(source, destination):
    width, height, fps = 1280, 720, 18
    with Image.open(source) as image:
        if image.format != "PNG" or not image.is_animated or image.size != (1920, 1080):
            raise ValueError("Expected the 1920×1080 animated PNG")
        # Build in memory so an invalid source cannot leave a partial output.
        payload = BytesIO()
        with ZipFile(payload, "w", ZIP_STORED) as archive:
            def store(name, data):
                archive.writestr(ZipInfo(name), data)

            store("desc.txt", f"{width} {height} {fps}\np 0 0 part0\n")
            count = 0
            for original in range(image.n_frames):
                image.seek(original)
                frame = image.convert("RGBA").resize((width, height), Image.Resampling.LANCZOS)
                background = Image.new("RGB", (width, height))
                background.paste(frame, mask=frame.getchannel("A"))
                encoded = BytesIO()
                background.save(encoded, "JPEG", quality=92, subsampling=0, optimize=True)
                data = encoded.getvalue()
                repeats = max(1, round(image.info["duration"] * fps / 1000))
                for _ in range(repeats):
                    if count >= 1500:
                        raise ValueError("Too many output frames")
                    store(f"part0/{count:05}.jpg", data)
                    count += 1
                if payload.tell() > 48 * 1024 * 1024:
                    raise ValueError("Animation exceeds the app's 48 MB size limit")
        if payload.tell() > 48 * 1024 * 1024:
            raise ValueError("ZIP exceeds the app's 48 MB size limit")
        with destination.open("xb") as output:
            output.write(payload.getbuffer())
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        count = convert(args.source, args.destination)
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"{error}\n")
    print(f"Created {args.destination} with {count} frames")


if __name__ == "__main__":
    main()
