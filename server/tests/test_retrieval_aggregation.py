"""Search ranks works by the mean of their top-3 chunk similarities, not by their single best chunk."""
import math

import pytest

from readerserver.models import SampleEmbedding, SourceRecord, Work, WorkLink
from readerserver.search.retrieval import knn_chunk_scores, knn_chunks


def vec(sim: float) -> list[float]:
    """Unit vector with cosine similarity `sim` to the query e0."""
    v = [0.0] * 1024
    v[0], v[1] = sim, math.sqrt(1 - sim * sim)
    return v


async def work(session, title, sims, status="ongoing"):
    w = Work(title=title, status=status)
    r = SourceRecord(source="t", source_id=title, title=title)
    session.add_all([w, r])
    await session.flush()
    session.add(WorkLink(source_record_id=r.id, work_id=w.id, method="new", confidence=1))
    for i, s in enumerate(sims):
        session.add(SampleEmbedding(source_record_id=r.id, chunk_index=i, chapter_index=i, model="m", embedding=vec(s)))
    await session.commit()
    return str(w.id)


QUERY = vec(1.0)


async def test_work_score_is_mean_of_top_three_chunks_padded_with_the_pool_cutoff(session):
    a = await work(session, "one great chunk only", [0.97])
    b = await work(session, "steady", [0.8, 0.8, 0.8])
    c = await work(session, "one spike", [0.95, 0.3, 0.3])
    d = await work(session, "huge novel", [0.5] * 200)  # volume must not win
    e = await work(session, "four chunks", [0.99, 0.8, 0.8, 0.7])  # the 4th is ignored
    got = await knn_chunk_scores(session, QUERY, [])
    # the pool holds every chunk, so its cutoff similarity is 0.3: a has only one chunk, the other two count as 0.3
    assert [w for w, _ in got] == [e, b, a, c, d]
    assert dict(got) == pytest.approx({a: (0.97 + 0.3 + 0.3) / 3, e: (0.99 + 0.8 + 0.8) / 3, b: 0.8, c: (0.95 + 0.3 + 0.3) / 3, d: (0.5 * 3) / 3}, abs=1e-6)
    assert await knn_chunks(session, QUERY, []) == [e, b, a, c, d]  # same order via the id-only wrapper used by search


async def test_single_chunk_work_does_not_beat_a_consistently_good_one(session):
    lone = await work(session, "lone", [0.9])  # old rule: best chunk 0.9 beats 0.85
    steady = await work(session, "steady", [0.85, 0.85, 0.85])
    far = await work(session, "far", [0.3, 0.3, 0.3])  # sets the pool cutoff the lone work's missing chunks are padded with
    got = [w for w, _ in await knn_chunk_scores(session, QUERY, [])]
    assert got == [steady, lone, far]


async def test_pool_grows_until_enough_distinct_works_are_covered(session):
    big = await work(session, "fully crawled novel", [0.9] * 60)  # every chapter embedded: it alone fills a small pool
    small = [await work(session, f"catalog only {i}", [0.5 - i * 0.01]) for i in range(5)]
    only_big = await knn_chunk_scores(session, QUERY, [], pool=10, min_works=3, ceiling=10)  # no growth allowed
    assert [w for w, _ in only_big] == [big]
    grown = await knn_chunk_scores(session, QUERY, [], pool=10, min_works=6)  # grows 10 -> 40 -> 160 until all 6 works appear
    assert {w for w, _ in grown} == {big, *small} and grown[0][0] == big
    assert len(await knn_chunk_scores(session, QUERY, [], pool=10, min_works=6, k=3)) == 3  # k still limits what comes back


async def test_candidate_pool_bounds_the_aggregation_and_k_limits_results(session):
    a = await work(session, "a", [0.9, 0.9, 0.9])
    b = await work(session, "b", [0.8, 0.8, 0.8])
    got = await knn_chunk_scores(session, QUERY, [], pool=4, min_works=1, ceiling=4)  # only the 4 nearest chunks: a x3, b x1 (+2 padded)
    assert dict(got) == pytest.approx({a: 0.9, b: (0.8 + 2 * 0.8) / 3}, abs=1e-6)
    assert [w for w, _ in await knn_chunk_scores(session, QUERY, [], k=1)] == [a]


async def test_filters_still_apply_before_aggregation(session):
    from readerserver.search.retrieval import filter_clauses
    from readerserver.search.schemas import SearchFilters

    await work(session, "ongoing one", [0.9, 0.9, 0.9], status="ongoing")
    done = await work(session, "completed one", [0.6, 0.6, 0.6], status="completed")
    got = await knn_chunks(session, QUERY, filter_clauses(SearchFilters(status="completed"), [], []))
    assert got == [done]


async def test_chunks_of_multiple_records_of_one_work_pool_together(session):
    w = Work(title="merged")
    r1, r2 = SourceRecord(source="a", source_id="1", title="m"), SourceRecord(source="b", source_id="1", title="m")
    session.add_all([w, r1, r2])
    await session.flush()
    for r, s in ((r1, 0.9), (r2, 0.7)):
        session.add(WorkLink(source_record_id=r.id, work_id=w.id, method="new", confidence=1))
        session.add(SampleEmbedding(source_record_id=r.id, chunk_index=0, model="m", embedding=vec(s)))
    await session.commit()
    [(wid, score)] = await knn_chunk_scores(session, QUERY, [])
    assert wid == str(w.id) and score == pytest.approx((0.9 + 0.7 + 0.7) / 3, abs=1e-6)  # 2 chunks, the 3rd padded with the 0.7 cutoff
