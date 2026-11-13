-- Blind measuring: the labelling page hides the ratios, the grades they imply and the measurer's answer
-- until the person presses "Done", which saves the label and shows the measurer's reading of the same
-- photo. revealed_at is when that happened, set as the row is written (the table is append-only).
-- Null marks a label made before blind measuring, when the person could work out a ratio while drawing,
-- so the scoreboard can tell the two kinds apart.
alter table centering_label add column revealed_at timestamptz;
