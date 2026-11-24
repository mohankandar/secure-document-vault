# Task 0089. Copy to C:\dev\sdv\testers\testers.settings.psd1 (outside the repository) and fill in. No secrets here:
# only names, addresses and the paths of files that hold secrets.
@{
    Host            = 'vero.example.com'
    TunnelId        = '00000000-0000-0000-0000-000000000000'
    CredentialsFile = 'C:\dev\sdv\secrets\vero-testers-tunnel.json'
    # Its own database and database user, never sdv.
    DatabaseUrl     = 'jdbc:postgresql://localhost:5433/sdv_testers'
    DatabaseUser    = 'vero_testers_app'
    # The only folder documents may be kept in.
    StorageFolder   = 'C:\dev\sdv\testers\storage'
    # The key service settings of this build, by environment name: the mount vero-transit and the testers' own customer
    # (runbook, "Remote testers"). Left empty, the start is refused.
    KeySettings     = @{}
}
