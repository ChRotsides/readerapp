from readerserver.text import (
    approx_tokens,
    authors_match,
    best_title_similarity,
    guess_language_from_script,
    normalize_title,
    title_similarity,
    trigram_similarity,
    version_marker,
)


def test_normalize_title():
    assert normalize_title("Re:Zero — Starting Life (WN)") == "re zero starting life"
    assert normalize_title("Ｔｈｅ　Ｂｅｇｉｎｎｉｎｇ  After the End [Light Novel]") == "the beginning after the end"
    assert normalize_title("無職転生　- 異世界行ったら本気だす -") == "無職転生 異世界行ったら本気だす"
    assert normalize_title("") == ""


def test_version_marker():
    assert version_marker("Overlord (LN)") == "LN"
    assert version_marker("Overlord [Web Novel]") == "WN"
    assert version_marker("Overlord") is None


def test_authors_match():
    assert authors_match(["Sun Rio"], ["Rio Sun"]) is True
    assert authors_match(["理不尽な孫の手"], ["理不尽な孫の手", "Rifujin na Magonote"]) is True
    assert authors_match(["A B"], ["C D"]) is False
    assert authors_match([], ["C D"]) is None


def test_similarity_orders_sensibly():
    same = title_similarity("mushoku tensei", "mushoku tensei jobless reincarnation")
    diff = title_similarity("mushoku tensei", "overlord")
    assert same > 0.4 > diff
    assert best_title_similarity(["Mushoku Tensei (LN)", "無職転生"], ["無職転生"]) == 1.0
    assert trigram_similarity("abc", "abc") == 1.0


def test_language_guess_and_tokens():
    assert guess_language_from_script("빌어먹을 환생") == "ko"
    assert guess_language_from_script("まぶらほ") == "ja"
    assert guess_language_from_script("无职转生") is None
    assert approx_tokens("abcd" * 100) >= 100
