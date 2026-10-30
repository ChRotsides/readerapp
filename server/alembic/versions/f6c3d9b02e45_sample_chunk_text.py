"""Persist embedding input text; legacy vectors remain nullable until safely matched.

Revision ID: f6c3d9b02e45
Revises: e5b2c8a91d34
"""
from alembic import op
import sqlalchemy as sa

revision = "f6c3d9b02e45"
down_revision = "e5b2c8a91d34"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("sample_embeddings", sa.Column("chunk_text", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("sample_embeddings", "chunk_text")
