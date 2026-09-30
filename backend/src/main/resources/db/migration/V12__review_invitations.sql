ALTER TABLE house ADD COLUMN review_only boolean NOT NULL DEFAULT false;

ALTER TABLE house_invitation ADD COLUMN granted_role varchar(20) NOT NULL DEFAULT 'RESIDENT'
    CHECK (granted_role IN ('RESIDENT', 'DISPATCHER'));
