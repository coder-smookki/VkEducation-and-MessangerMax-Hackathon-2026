"""Create analysis runs.

Revision ID: 0001_create_analysis_runs
Revises:
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0001_create_analysis_runs"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "analysis_runs",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("case_id", sa.Uuid(), nullable=False),
        sa.Column("report_id", sa.Uuid(), nullable=False),
        sa.Column("source_revision", sa.Integer(), nullable=False),
        sa.Column("model_version", sa.String(length=200), nullable=False),
        sa.Column("mode", sa.String(length=32), nullable=False),
        sa.Column("response", sa.JSON(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_analysis_runs_case_id", "analysis_runs", ["case_id"])
    op.create_index("ix_analysis_runs_report_id", "analysis_runs", ["report_id"])


def downgrade() -> None:
    op.drop_index("ix_analysis_runs_report_id", table_name="analysis_runs")
    op.drop_index("ix_analysis_runs_case_id", table_name="analysis_runs")
    op.drop_table("analysis_runs")
