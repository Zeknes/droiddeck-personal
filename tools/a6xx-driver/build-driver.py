#!/usr/bin/env python3
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import hashlib
import http.client
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'app/build/prepared-assets/graphics_driver/adrenotools-turnip26.3.0-a6xx.tzst'
A6XX_TAG = 'v26.3.0-20261012-r2'
A6XX_SHA256 = 'bb1b2c51e6446cf428d70255d037be169ac68108c00af33aa1ba7ece711f8336'
A6XX_URL = f'https://github.com/The412Banner/Banners-Turnip/releases/download/{A6XX_TAG}/Turnip-{A6XX_TAG}.zip'


def pack(entries, output):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, content in entries:
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            entry.mode = 0o644
            archive.addfile(entry, io.BytesIO(content))
    compressed = subprocess.run(['zstd', '-q', '-19', '-c'], input=stream.getvalue(),
                                capture_output=True, check=True).stdout
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(compressed)
    print(output)


def download(url, attempts=4):
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                return response.read()
        except (OSError, http.client.HTTPException) as error:
            if attempt == attempts or isinstance(error, urllib.error.HTTPError) and error.code < 500 and error.code != 429:
                raise
            print(f'Download failed ({error}); retrying in {attempt * 15} s')
            time.sleep(attempt * 15)


def build_a6xx(output_dir=OUTPUT.parent, archive_path=None):
    cached = archive_path or ROOT / 'app/build/a6xx-inputs' / f'Turnip-{A6XX_TAG}.zip'
    if not cached.exists():
        cached.parent.mkdir(parents=True, exist_ok=True)
        data = download(A6XX_URL)
        if hashlib.sha256(data).hexdigest() != A6XX_SHA256:
            raise ValueError('A6xx archive checksum mismatch')
        cached.write_bytes(data)
    data = cached.read_bytes()
    if hashlib.sha256(data).hexdigest() != A6XX_SHA256:
        raise ValueError('A6xx archive checksum mismatch')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        driver = archive.read('libvulkan_freedreno.so')
        meta = json.loads(archive.read('meta.json'))
    if meta.get('libraryName') != 'libvulkan_freedreno.so':
        raise ValueError('Unexpected A6xx driver library')
    meta['name'] = 'Turnip Adreno 6xx - bundled fallback'
    provenance = (f'Banners-Turnip {A6XX_TAG}\n{A6XX_URL}\nSHA256 {A6XX_SHA256}\n'
                  'Mesa source: a0ff50acf0e1d152f54949490fa41558d2e0f7a4\n'
                  'Packaged by DroidDeck tools/a6xx-driver (GPL-3.0-or-later)\n').encode()
    entries = [('libvulkan_freedreno.so', driver), ('SOURCE.txt', provenance),
               ('meta.json', (json.dumps(meta, indent=2) + '\n').encode())]
    pack(entries, output_dir / 'adrenotools-turnip26.3.0-a6xx.tzst')


if __name__ == '__main__':
    build_a6xx()
