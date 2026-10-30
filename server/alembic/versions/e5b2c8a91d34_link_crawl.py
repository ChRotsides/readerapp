"""link crawl: site recipes, link crawl jobs, chapter-level sample embeddings

Revision ID: e5b2c8a91d34
Revises: d3a1f0b7c2e4
Create Date: 2026-10-30 18:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = 'e5b2c8a91d34'
down_revision: Union[str, Sequence[str], None] = 'd3a1f0b7c2e4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('sample_embeddings', sa.Column('chapter_index', sa.Integer(), nullable=True))
    op.add_column('sample_embeddings', sa.Column('chapter_hash', sa.String(length=64), nullable=True))
    op.drop_constraint('uq_sample_chunk', 'sample_embeddings', type_='unique')
    op.create_index('uq_sample_chunk', 'sample_embeddings', ['source_record_id', 'chunk_index'], unique=True,
                    postgresql_where=sa.text('chapter_index IS NULL'))
    op.create_index('uq_chapter_chunk', 'sample_embeddings', ['source_record_id', 'chapter_index', 'chunk_index'], unique=True,
                    postgresql_where=sa.text('chapter_index IS NOT NULL'))

    op.create_table(
        'site_recipes',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('domain', sa.String(length=255), nullable=False),
        sa.Column('version', sa.Integer(), nullable=False),
        sa.Column('recipe', postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column('status', sa.String(length=12), nullable=False, server_default='active'),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('notes', sa.Text(), nullable=True),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('domain', 'version', name='uq_site_recipe_version'),
    )
    op.create_index('uq_site_recipe_active', 'site_recipes', ['domain'], unique=True, postgresql_where=sa.text("status = 'active'"))

    op.create_table(
        'link_crawl_jobs',
        sa.Column('id', sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column('url', sa.Text(), nullable=False),
        sa.Column('domain', sa.String(length=255), nullable=False),
        sa.Column('scope', sa.String(length=10), nullable=False),
        sa.Column('chapters_per_novel', sa.Integer(), nullable=True),
        sa.Column('status', sa.String(length=10), nullable=False, server_default='queued'),
        sa.Column('pages_fetched', sa.Integer(), server_default=sa.text('0'), nullable=False),
        sa.Column('novels_found', sa.Integer(), server_default=sa.text('0'), nullable=False),
        sa.Column('chapters_embedded', sa.Integer(), server_default=sa.text('0'), nullable=False),
        sa.Column('chapters_skipped', sa.Integer(), server_default=sa.text('0'), nullable=False),
        sa.Column('spend_usd', sa.Numeric(12, 6), server_default=sa.text('0'), nullable=False),
        sa.Column('error', sa.Text(), nullable=True),
        sa.Column('checkpoint', postgresql.JSONB(astext_type=sa.Text()), server_default='{}', nullable=False),
        sa.Column('attempts', sa.Integer(), server_default=sa.text('0'), nullable=False),
        sa.Column('lease_owner', sa.String(length=40), nullable=True),
        sa.Column('lease_expires_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('next_eligible_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index('ix_link_crawl_jobs_status', 'link_crawl_jobs', ['status'])
    op.create_index('ix_link_crawl_jobs_domain', 'link_crawl_jobs', ['domain'])


def downgrade() -> None:
    op.drop_table('link_crawl_jobs')
    op.drop_index('uq_site_recipe_active', table_name='site_recipes')
    op.drop_table('site_recipes')
    op.execute('DELETE FROM sample_embeddings WHERE chapter_index IS NOT NULL')
    op.drop_index('uq_chapter_chunk', table_name='sample_embeddings')
    op.drop_index('uq_sample_chunk', table_name='sample_embeddings')
    op.create_unique_constraint('uq_sample_chunk', 'sample_embeddings', ['source_record_id', 'chunk_index'])
    op.drop_column('sample_embeddings', 'chapter_hash')
    op.drop_column('sample_embeddings', 'chapter_index')
