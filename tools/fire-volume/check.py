"""Check that the baked loop neither freezes nor jumps at its wrap boundary."""
from pathlib import Path
import gzip
import struct
import numpy as np

path = Path(__file__).resolve().parents[2] / 'src/nurgling/render/assets/fire-volume.bin.gz'
with gzip.open(path, 'rb') as stream:
    magic, width, height, depth, count = struct.unpack('>5i', stream.read(20))
    assert (magic, width, height, depth, count) == (0x4e464952, 64, 64, 96, 64)
    frames = np.frombuffer(stream.read(), np.uint8).reshape(count, depth, height, width, 2)
density = frames[..., 0].astype(np.float32) / 255
motion = np.mean(np.abs(np.roll(density, -1, axis=0) - density), axis=(1, 2, 3))
ordinary = np.median(motion[:45])
assert ordinary > 0, 'Static combustion cache'
assert .5 < motion[-1] / ordinary < 2, 'Loop slows down or jumps at seam'
assert np.min(motion[-8:]) / ordinary > .4, 'Loop tail freezes'
print('Loop motion: PASS (seam/ordinary %.3f)' % (motion[-1] / ordinary))
