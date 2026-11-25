-- Task 0091: the version of the stored document; its ciphertext is bound to it. One more with every redaction, never less.
alter table document_crypto_metadata add column object_version integer not null default 1;
alter table document_crypto_metadata add constraint ck_document_crypto_object_version check (object_version > 0);

create function document_object_version_only_rises() returns trigger language plpgsql as $$
begin
    raise exception 'a document''s object version only rises' using errcode = '23514';
end;
$$;

create trigger trg_document_object_version
    before update on document_crypto_metadata
    for each row when (new.object_version < old.object_version)
    execute function document_object_version_only_rises();
