"""Partial HNSW index over catalog samples + early chapters (< 10) for /similar's beginnings-vs-beginnings coverage kNN.

Revision ID: e1b7a3d5c902
Revises: d0a4b6c9f235
"""
from alembic import op

revision = "e1b7a3d5c902"
down_revision = "d0a4b6c9f235"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index("ix_sample_embeddings_early_hnsw", "sample_embeddings", ["embedding"], postgresql_using="hnsw",
                    postgresql_ops={"embedding": "vector_cosine_ops"}, postgresql_where="chapter_index IS NULL OR chapter_index < 10")


def downgrade() -> None:
    op.drop_index("ix_sample_embeddings_early_hnsw", table_name="sample_embeddings")
