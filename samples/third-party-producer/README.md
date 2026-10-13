# Standalone Third-Party Producer Sample

This sample demonstrates SDV V1 ingestion using ordinary HTTP calls only. It imports no SDV code, libraries, database schemas or internal domain models.

Production usage supplies an OAuth2/OIDC bearer token with `-AccessToken`. The `-LocalDevTenantId` and `-LocalDevSourceId` switches exist only so repository acceptance can exercise the exact same producer script against SDV's local-header development adapter.

The sample performs the full producer lifecycle:

1. create a submission with producer-owned identities and source-configured metadata;
2. replay the exact create request and verify idempotency;
3. upload PDFs independently with stable source document IDs and upload idempotency keys;
4. optionally replay the first PDF and verify the same SDV document is returned;
5. retrieve the completed submission by both SDV submission ID and producer-owned `sourceSubmissionId`;
6. list received documents.

The sample must remain standalone. Do not add imports or dependencies on the SDV repository.
