import uuid
from datetime import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

DIM = 1024


class Base(DeclarativeBase):
    pass


def _now():
    return func.now()


class Work(Base):
    """Canonical novel. Fields are derived from linked source records (see resolution/merge.py)."""

    __tablename__ = "works"
    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    title: Mapped[str] = mapped_column(Text)
    native_title: Mapped[str | None] = mapped_column(Text)
    aliases: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")
    authors: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")
    synopsis: Mapped[str | None] = mapped_column(Text)
    synopsis_source: Mapped[str | None] = mapped_column(String(40))
    tags: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")  # canonical names
    genres: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")
    status: Mapped[str | None] = mapped_column(String(20))  # ongoing|completed|hiatus|cancelled
    chapter_count: Mapped[int | None] = mapped_column(Integer)
    language: Mapped[str | None] = mapped_column(String(8))  # original language
    start_year: Mapped[int | None] = mapped_column(Integer)
    cover_url: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now(), onupdate=_now())
    __table_args__ = (
        Index("ix_works_language", "language"),
        Index("ix_works_status", "status"),
        Index("ix_works_tags", "tags", postgresql_using="gin"),
    )


class SourceRecord(Base):
    """Raw per-site metadata, kept exactly as crawled. Never merged or overwritten by other sources."""

    __tablename__ = "source_records"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    source: Mapped[str] = mapped_column(String(40))
    source_id: Mapped[str] = mapped_column(String(200))
    url: Mapped[str | None] = mapped_column(Text)
    title: Mapped[str] = mapped_column(Text)
    alt_titles: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")
    authors: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")
    synopsis: Mapped[str | None] = mapped_column(Text)
    tags: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")
    genres: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")
    status: Mapped[str | None] = mapped_column(String(20))
    chapter_count: Mapped[int | None] = mapped_column(Integer)
    rating: Mapped[float | None] = mapped_column(Float)
    rating_scale: Mapped[float | None] = mapped_column(Float)
    rating_votes: Mapped[int | None] = mapped_column(Integer)
    language: Mapped[str | None] = mapped_column(String(8))
    start_year: Mapped[int | None] = mapped_column(Integer)
    cover_url: Mapped[str | None] = mapped_column(Text)
    external_ids: Mapped[dict] = mapped_column(JSONB, default=dict, server_default="{}")  # {"mal": "123", ...}
    related_urls: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list, server_default="{}")
    raw: Mapped[dict] = mapped_column(JSONB, default=dict, server_default="{}")
    content_hash: Mapped[str | None] = mapped_column(String(64))
    synopsis_embedding = mapped_column(Vector(DIM), nullable=True)  # used for ER kNN
    needs_resolution: Mapped[bool] = mapped_column(Boolean, default=True, server_default=text("true"))
    # Deferred resolution (prerequisite missing, e.g. synopsis embedding / verdict provider down): stays pending, retried later.
    resolve_retry_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    resolve_reason: Mapped[str | None] = mapped_column(String(20))  # awaiting_embedding | pending_verdict
    resolve_attempts: Mapped[int] = mapped_column(Integer, default=0, server_default=text("0"))
    needs_index: Mapped[bool] = mapped_column(Boolean, default=True, server_default=text("true"))
    samples_done: Mapped[bool] = mapped_column(Boolean, default=False, server_default=text("false"))
    crawled_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())
    __table_args__ = (
        UniqueConstraint("source", "source_id", name="uq_source_record"),
        Index("ix_source_records_pending", "needs_resolution"),
        Index(
            "ix_source_records_synopsis_hnsw",
            "synopsis_embedding",
            postgresql_using="hnsw",
            postgresql_ops={"synopsis_embedding": "vector_cosine_ops"},
        ),
    )


class SourceTitle(Base):
    """Normalized title + aliases per source record, trigram indexed for candidate generation."""

    __tablename__ = "source_titles"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    source_record_id: Mapped[int] = mapped_column(ForeignKey("source_records.id", ondelete="CASCADE"))
    norm_title: Mapped[str] = mapped_column(Text)
    __table_args__ = (
        Index("ix_source_titles_record", "source_record_id"),
        Index("ix_source_titles_trgm", "norm_title", postgresql_using="gin", postgresql_ops={"norm_title": "gin_trgm_ops"}),
    )


class WorkLink(Base):
    """source_record -> work. Reversible: undoing sets undone_at; nothing is deleted."""

    __tablename__ = "work_links"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    source_record_id: Mapped[int] = mapped_column(ForeignKey("source_records.id", ondelete="CASCADE"))
    work_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"))
    method: Mapped[str] = mapped_column(String(40))  # new|hard_id|hard_alias|auto_score|jev|claude|manual
    confidence: Mapped[float] = mapped_column(Float)
    evidence: Mapped[dict] = mapped_column(JSONB, default=dict, server_default="{}")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())
    undone_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    __table_args__ = (
        Index("ix_work_links_work", "work_id"),
        Index(
            "uq_work_links_active",
            "source_record_id",
            unique=True,
            postgresql_where=text("undone_at IS NULL"),
        ),
    )


class LinkBlock(Base):
    """Created on split: never auto-merge this record into this work again."""

    __tablename__ = "link_blocks"
    source_record_id: Mapped[int] = mapped_column(ForeignKey("source_records.id", ondelete="CASCADE"), primary_key=True)
    work_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())


class LinkSeparation(Base):
    """Symmetric 'do not merge' constraint between two source records (created when one is split out of the other's
    work). Stored once per unordered pair (record_a_id < record_b_id) and enforced in both directions."""

    __tablename__ = "link_separations"
    record_a_id: Mapped[int] = mapped_column(ForeignKey("source_records.id", ondelete="CASCADE"), primary_key=True)
    record_b_id: Mapped[int] = mapped_column(ForeignKey("source_records.id", ondelete="CASCADE"), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())
    __table_args__ = (CheckConstraint("record_a_id < record_b_id", name="ck_link_separation_order"),
                      Index("ix_link_separations_b", "record_b_id"))


class WorkRelation(Base):
    __tablename__ = "work_relations"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    work_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"))
    related_work_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"))
    relation_type: Mapped[str] = mapped_column(String(30))  # LN_VERSION|SEQUEL|SIDE_STORY|ALT_TRANSLATION|RELATED
    method: Mapped[str] = mapped_column(String(40))
    confidence: Mapped[float] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())
    __table_args__ = (UniqueConstraint("work_id", "related_work_id", "relation_type", name="uq_work_relation"),)


class CanonicalTag(Base):
    __tablename__ = "canonical_tags"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(Text, unique=True)
    origin: Mapped[str] = mapped_column(String(40), default="novelupdates")  # base vocabulary vs auto-created


class TagMapping(Base):
    __tablename__ = "tag_mappings"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    source: Mapped[str] = mapped_column(String(40))  # source name or '*' for any
    source_tag: Mapped[str] = mapped_column(Text)  # normalized
    canonical_tag_id: Mapped[int] = mapped_column(ForeignKey("canonical_tags.id", ondelete="CASCADE"))
    __table_args__ = (UniqueConstraint("source", "source_tag", name="uq_tag_mapping"),)


class ProfileEmbedding(Base):
    __tablename__ = "profile_embeddings"
    work_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("works.id", ondelete="CASCADE"), primary_key=True)
    text_hash: Mapped[str] = mapped_column(String(64))
    model: Mapped[str] = mapped_column(String(40))
    embedding = mapped_column(Vector(DIM))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now(), onupdate=_now())
    __table_args__ = (
        Index(
            "ix_profile_embeddings_hnsw",
            "embedding",
            postgresql_using="hnsw",
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
    )


class SampleEmbedding(Base):
    """Vectors of sample-chapter chunks. Chapter text is never stored."""

    __tablename__ = "sample_embeddings"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    source_record_id: Mapped[int] = mapped_column(ForeignKey("source_records.id", ondelete="CASCADE"))
    chunk_index: Mapped[int] = mapped_column(Integer)
    token_count: Mapped[int] = mapped_column(Integer, default=0)
    model: Mapped[str] = mapped_column(String(40))
    embedding = mapped_column(Vector(DIM))
    __table_args__ = (
        UniqueConstraint("source_record_id", "chunk_index", name="uq_sample_chunk"),
        Index(
            "ix_sample_embeddings_hnsw",
            "embedding",
            postgresql_using="hnsw",
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
    )


class CrawlState(Base):
    __tablename__ = "crawl_state"
    source: Mapped[str] = mapped_column(String(40), primary_key=True)
    status: Mapped[str] = mapped_column(String(20), default="idle")  # idle|running|blocked|error|disabled
    checkpoint: Mapped[dict] = mapped_column(JSONB, default=dict, server_default="{}")
    requested_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_error: Mapped[str | None] = mapped_column(Text)
    next_eligible_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))  # crawl deferred (Retry-After) until
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))  # heartbeat lease while running
    blocked_reason: Mapped[str | None] = mapped_column(Text)
    items_seen: Mapped[int] = mapped_column(Integer, default=0)


class CrawlRun(Base):
    __tablename__ = "crawl_runs"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    source: Mapped[str] = mapped_column(String(40))
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    status: Mapped[str] = mapped_column(String(20), default="running")
    stats: Mapped[dict] = mapped_column(JSONB, default=dict, server_default="{}")


class AiUsage(Base):
    __tablename__ = "ai_usage"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    ts: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())
    provider: Mapped[str] = mapped_column(String(20))  # anthropic|voyage|jev
    model: Mapped[str] = mapped_column(String(60))
    purpose: Mapped[str] = mapped_column(String(40))
    input_tokens: Mapped[int] = mapped_column(Integer, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, default=0)
    cost_usd: Mapped[float] = mapped_column(Numeric(12, 6), default=0)
    __table_args__ = (Index("ix_ai_usage_ts", "ts"),)


class SpendReservation(Base):
    """Budget held for an in-flight paid call; replaced by its ai_usage row on settle, deleted on release/expiry."""

    __tablename__ = "ai_spend_reservations"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    ts: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=_now())
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    amount_usd: Mapped[float] = mapped_column(Numeric(12, 6), default=0)
    purpose: Mapped[str] = mapped_column(String(40), default="")
