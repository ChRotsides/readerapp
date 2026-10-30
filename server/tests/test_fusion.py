from readerserver.ai.embeddings import make_batches
from readerserver.indexing import chunk_text
from readerserver.search.fusion import fuse, rrf
from readerserver.text import approx_tokens


def test_rrf_value():
    assert rrf(1, 60) == 1 / 61


def test_multi_scene_match_beats_single_top_hit():
    # A is #1 in scene 0 only; B is #2 in all three scenes
    scenes = [["A", "B"], ["X", "B"], ["Y", "B"]]
    out = fuse(scenes)
    assert out[0].work_id == "B" and out[0].scene_matches == 3
    a = next(f for f in out if f.work_id == "A")
    assert a.scene_matches == 1 and out[0].score > a.score


def test_profile_lists_blend_and_weights():
    out = fuse([["A"]], {"synopsis": ["B", "A"], "scene0": ["B", "C"]}, {"synopsis": 1.0, "scene0": 0.5})
    ids = [f.work_id for f in out]
    assert ids[0] == "A"  # chunk hit + profile hit
    assert ids.index("B") < ids.index("C")  # B is in both profile lists, higher
    b = next(f for f in out if f.work_id == "B")
    assert b.detail["profile"] > 0 and b.scene_matches == 0
    assert fuse([]) == []


def test_chunking_targets_size_and_never_exceeds_max():
    para = ("word " * 150).strip()
    text = "\n\n".join([para] * 20)
    chunks = chunk_text(text)
    assert len(chunks) > 1
    assert all(approx_tokens(c) <= 1000 for c in chunks)
    assert 500 <= approx_tokens(chunks[0]) <= 1000
    assert chunk_text("") == []
    giant = ("This is a sentence. " * 400).strip()
    assert all(approx_tokens(c) <= 1000 for c in chunk_text(giant))


def test_make_batches_limits():
    texts = ["a" * 4000] * 10  # ~1000 tokens each
    b = make_batches(texts, max_items=4, max_tokens=2500)
    assert all(len(x) <= 2 for x in b) and sum(len(x) for x in b) == 10
