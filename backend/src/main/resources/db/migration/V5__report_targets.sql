-- A report names the items it may concern; only a dispatcher's link makes it actually concern one.
CREATE TABLE report_target_item (
    report_id uuid NOT NULL,
    item_id uuid NOT NULL,
    case_id uuid NOT NULL,
    PRIMARY KEY (report_id, item_id),
    FOREIGN KEY (report_id, case_id) REFERENCES work_report(id, case_id),
    FOREIGN KEY (item_id, case_id) REFERENCES case_item(id, case_id)
);
CREATE INDEX report_target_report_idx ON report_target_item(report_id);
