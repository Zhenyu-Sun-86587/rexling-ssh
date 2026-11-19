# Connecting Rexling

Open **Your hosts → Add host** to enter an SSH or HTTPS URL. The default uses your phone’s network, including an existing Tailscale VPN. Existing Go SSH and HTTPS servers keep working.

## SSH

Enter `ssh://user@host`, optionally with a port (`ssh://user@host:2222`). The host needs Rex installed and an SSH server that permits Unix socket forwarding. Unless the remote `REX_AUTOSTART` setting disables it, Rexling can start that user’s Rex server when discovering its socket. Linux and macOS hosts are supported; Windows named-pipe hosts are not yet supported over SSH.

Import an OpenSSH or PEM private key in the server editor. Encrypted keys prompt for a passphrase during import. The imported key is stored encrypted using Android Keystore; its passphrase is not retained. This authenticates the SSH connection and does not provide SSH agent forwarding. Password authentication and OpenSSH user certificates are not supported.

For Tailscale SSH, leave the key unset. This works when the host accepts keyless authentication under its Tailscale SSH policy. Browser approval messages appear automatically, with Copy Message and Open Browser actions.

Before first connection, verify the displayed host fingerprint against the host. A changed key requires a new explicit trust decision. Removing a server removes its key assignment, but the key remains available in the SSH key library.

An explicit remote Rex socket can be specified as a URL path, such as `ssh://user@host/tmp/rex.sock`. Otherwise Rexling uses Rex’s CLI to discover the socket. A missing Rex installation is reported rather than installed automatically.

### SSH jump host

Add a gateway with `ssh://user@gateway`, enable **Jump host only**, choose its network and SSH key, and tap **Save only**. It appears under **SSH jump hosts** and doesn’t need Rex installed. Its SSH server must allow TCP forwarding to the target’s SSH port.

In the target’s SSH editor, tap **Choose a saved SSH host**. You can pick the gateway or any eligible saved direct SSH login. The gateway and target authenticate separately and both host keys must be verified. Rex is discovered or started only on the target. Only one jump is supported; if it fails, Rexling does not fall back to direct SSH. To remove a jump, open the picker and select **Use direct SSH** before forgetting the gateway.

### Upgrading an old Rust SSH profile

Old Rust SSH entries, including jump-only hosts, are marked **Setup required** and never reconnect automatically. Tap one to edit its URL and select a route and key. The old SSH credentials and fingerprints are not imported. To restore a jump route, save the jump host first, then select it in the target’s editor.

## Built-in Tailscale and Headscale

Open Preferences → Network, enable Built-in Tailscale and tap Apply. Tap Sign in, then open the sign-in page. This creates a separate `rexling-android` tailnet device and does not require the Tailscale VPN app. Your tailnet’s access rules still apply.

Leave Control server blank for Tailscale, or enter your Headscale server’s HTTP(S) URL. Each control server retains its own private sign-in state when switching. HTTPS must use a certificate trusted by the phone; certificate checks are not bypassed.

With built-in Tailscale on, Rexling’s SSH and HTTPS connections use it. Use a tailnet short name, full DNS name, or IP address. Active peer routes are listed as direct, peer relay or Tailscale relay. Existing terminal connections reconnect after changing network settings; active uploads and forwards may need restarting.

Turn built-in Tailscale off to return to the phone’s existing network or VPN. No reverse forwarding or exit-node setup is included.
