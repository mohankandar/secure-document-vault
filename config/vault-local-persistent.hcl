# Development-only persistence. No TLS; compose.local.yml publishes the port on 127.0.0.1 only.
ui = false
disable_mlock = true
storage "raft" {
  path = "/vault/data"
  node_id = "sdv-local-1"
}
listener "tcp" {
  address = "0.0.0.0:8200"
  tls_disable = 1
}
api_addr = "http://127.0.0.1:8200"
cluster_addr = "https://127.0.0.1:8201"
