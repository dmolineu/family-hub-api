-- The order a family's members are listed in. Until now members were read with no
-- ORDER BY, so they came back in the table's storage order, and editing a member
-- moved them in the Menu and header. That order is already shuffled, so each family
-- is numbered from 0 the way families are usually listed: parents first, then
-- oldest to youngest, those without a birthday after those with one. Anything still
-- tied keeps its storage order (ctid), which is the order the screens show today.
-- The app always sets the column; the default is only for rows inserted by hand,
-- such as test fixtures.
ALTER TABLE family_member ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

UPDATE family_member m
SET sort_order = numbered.position
FROM (SELECT id,
             ROW_NUMBER() OVER (PARTITION BY family_id
                                ORDER BY CASE WHEN role = 'PARENT' THEN 0 ELSE 1 END,
                                         birth_year, birth_month, birth_day, ctid) - 1 AS position
      FROM family_member) numbered
WHERE m.id = numbered.id;
