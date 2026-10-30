from types import SimpleNamespace as NS

from readerserver.resolution.merge import canonicalize_tags, merge_records, ratings_for
from readerserver.resolution.scoring import decide, hard_match, score_pair
from readerserver.resolution.tag_seed import BASE_TAGS


def rec(**kw):
    base = dict(id=1, source="x", source_id="1", url=None, title="", alt_titles=[], authors=[], synopsis=None, tags=[], genres=[],
                status=None, chapter_count=None, rating=None, rating_scale=None, rating_votes=None, language=None,
                start_year=None, cover_url=None, external_ids={}, related_urls=[], synopsis_embedding=None)
    base.update(kw)
    return NS(**base)


def test_hard_match_cross_ids():
    a = rec(source="anilist", external_ids={"anilist": "9", "mal": "120"})
    b = rec(source="mal", external_ids={"mal": "120"})
    assert hard_match(a, b)[0] == "hard_id"
    assert hard_match(a, rec(source="mal", external_ids={"mal": "121"})) is None


def test_descriptive_url_crossref_is_not_a_hard_match_but_scores():
    from readerserver.resolution.scoring import cross_linked, score_pair

    a = rec(source="mangaupdates", title="Alpha Saga", related_urls=["https://novel.munpia.com/222360"])
    b = rec(source="munpia", title="Alpha Sagas", url="http://novel.munpia.com/222360/")
    assert hard_match(a, b) is None and cross_linked(a, b) and cross_linked(b, a)
    plain = rec(source="munpia", title="Alpha Sagas", url="http://novel.munpia.com/999/")
    assert score_pair(a, b).total > score_pair(a, plain).total  # evidence only


def test_descriptive_mal_link_in_related_urls_is_not_identity():
    from readerserver.resolution.scoring import cross_linked

    a = rec(source="mangaupdates", title="X", related_urls=["https://myanimelist.net/manga/120/Sequel"], external_ids={"mangaupdates": "1"})
    b = rec(source="mal", title="Y", external_ids={"mal": "120"})
    assert hard_match(a, b) is None and cross_linked(a, b)


def test_version_conflict_blocks_even_authoritative_id_hard_match():
    a = rec(source="anilist", title="Shadow Slave (WN)", external_ids={"mal": "5"})
    b = rec(source="mal", title="Shadow Slave (LN)", external_ids={"mal": "5"})
    assert hard_match(a, b) is None


def test_hard_match_alias_incl_native_title():
    a = rec(source="syosetu", title="無職転生　- 異世界行ったら本気だす -", authors=["理不尽な孫の手"], language="ja")
    b = rec(source="novelupdates", title="Mushoku Tensei", alt_titles=["無職転生 異世界行ったら本気だす"], authors=["Rifujin na Magonote"], language="ja")
    assert hard_match(a, b)[0] == "hard_alias"
    # contradicting author blocks the alias bridge
    c = rec(source="novelupdates", title="x", alt_titles=["無職転生 異世界行ったら本気だす"], authors=["別の作者"], language="ja")
    assert hard_match(a, c) is None
    # short generic aliases are not distinctive
    assert hard_match(rec(source="a", title="Love"), rec(source="b", title="Love")) is None


def test_score_auto_merge():
    a = rec(source="novelupdates", title="The Beginning After The End", authors=["TurtleMe"], language="en", start_year=2018, chapter_count=450)
    b = rec(source="royalroad", title="The Beginning After the End (WN)", authors=["TurtleMe"], language="en", start_year=2018, chapter_count=440)
    s = score_pair(a, b, synopsis_cos=0.8)
    assert s.total >= 0.85 and decide(s) == "merge"


def test_score_separate_for_unrelated():
    a = rec(title="Overlord", authors=["Kugane Maruyama"], language="ja", start_year=2010)
    b = rec(title="Cooking With Wild Game", authors=["Someone"], language="ja", start_year=2019)
    s = score_pair(a, b, synopsis_cos=0.3)
    assert decide(s) == "separate"


def test_author_mismatch_prevents_auto_merge():
    a = rec(title="Dragon King", authors=["Alice A"], language="en", start_year=2020)
    b = rec(title="Dragon King", authors=["Bob B"], language="en", start_year=2020)
    s = score_pair(a, b, synopsis_cos=0.85)
    assert s.author is False and s.total <= 0.7 and decide(s) == "verdict"


def test_language_mismatch_penalised():
    a = rec(title="Shadow Slave", language="en")
    b = rec(title="Shadow Slave", language="ko")
    assert score_pair(a, b).language_match is False
    assert score_pair(a, b).total < score_pair(a, rec(title="Shadow Slave", language="en")).total


def test_wn_vs_ln_forces_verdict():
    a = rec(title="Mushoku Tensei (WN)", authors=["Same"], language="ja", start_year=2012)
    b = rec(title="Mushoku Tensei (LN)", authors=["Same"], language="ja", start_year=2012)
    s = score_pair(a, b, synopsis_cos=0.95)
    assert s.marker_conflict and decide(s) == "verdict"


def test_merge_fields_official_synopsis_first_tags_unioned_ratings_per_source():
    mapping = {("*", "isekai"): "Transported to Another World"}
    vocab = {t.lower(): t for t in BASE_TAGS}
    syo = rec(id=1, source="syosetu", title="無職転生", synopsis="日本語のあらすじがここにあります。" * 2, tags=["異世界"], authors=["理不尽な孫の手"],
              language="ja", start_year=2012, chapter_count=286)
    anilist = rec(id=2, source="anilist", title="Mushoku Tensei: Jobless Reincarnation", alt_titles=["Mushoku Tensei"], synopsis="English synopsis that is long enough to count.",
                  tags=["Isekai", "Magic", "Weird Tag"], rating=82, rating_scale=100, genres=["Fantasy"], cover_url="http://c/1.jpg", chapter_count=300, start_year=2014)
    mu = rec(id=3, source="mangaupdates", title="Mushoku Tensei (Novel)", rating=7.5, rating_scale=10, rating_votes=100, tags=["isekai"])
    m = merge_records([anilist, mu, syo], mapping, vocab)
    assert m.synopsis_source == "syosetu"  # official first
    assert m.title == "Mushoku Tensei: Jobless Reincarnation" and m.native_title == "無職転生"
    assert "Transported to Another World" in m.tags and m.tags.count("Transported to Another World") == 1
    assert "Magic" in m.tags and "Weird Tag" in m.tags
    assert m.start_year == 2012 and m.chapter_count == 300 and m.cover_url == "http://c/1.jpg"
    rs = ratings_for([anilist, mu, syo])
    assert {r["source"]: r["normalized"] for r in rs} == {"anilist": 8.2, "mangaupdates": 7.5}


def test_canonicalize_unknown_japanese_kept():
    assert canonicalize_tags(["ざまぁ系"], "syosetu", {}, {}) == ["ざまぁ系"]
