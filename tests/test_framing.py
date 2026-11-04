import pytest

from robot_recon.hal.framing import FrameDecoder, crc16_ccitt_false, encode_frame


def test_known_crc_vector() -> None:
    assert crc16_ccitt_false(b"123456789") == 0x29B1


def test_frame_round_trip() -> None:
    decoder = FrameDecoder()
    assert decoder.feed(encode_frame(b"hello")) == [b"hello"]


def test_incremental_and_multiple_frames() -> None:
    stream = encode_frame(b"one") + encode_frame(b"two")
    decoder = FrameDecoder()
    received = []
    for byte in stream:
        received.extend(decoder.feed(bytes([byte])))
    assert received == [b"one", b"two"]


def test_corrupt_frame_resynchronizes() -> None:
    good = encode_frame(b"payload")
    corrupt = bytearray(good)
    corrupt[-1] ^= 0xFF
    decoder = FrameDecoder()
    assert decoder.feed(bytes(corrupt) + good) == [b"payload"]
    assert decoder.stats.bad_frames == 1


def test_noise_before_header_is_counted() -> None:
    decoder = FrameDecoder()
    assert decoder.feed(b"noise" + encode_frame(b"x")) == [b"x"]
    assert decoder.stats.discarded_bytes == 5


def test_oversized_payload_is_rejected_on_encode() -> None:
    with pytest.raises(ValueError):
        encode_frame(b"x" * 256)


def test_empty_payload() -> None:
    decoder = FrameDecoder()
    assert decoder.feed(encode_frame(b"")) == [b""]
