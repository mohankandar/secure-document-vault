-- Task 0082 (approvals from messages): the one-click links' request of platform-core's notifications that belongs to a
-- Vero request. Vero's access_requests stays the one truth; the link's request is closed when Vero decides. Null for
-- requests without links (made before, links off, or a kind decided in its own review).
alter table access_requests add column link_request_id uuid null;
create unique index uq_access_requests_link_request on access_requests (tenant_id, link_request_id) where link_request_id is not null;
