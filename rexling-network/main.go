// Package main provides the app-private Tailscale and SSH byte transports.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/crypto/ssh"
	"golang.org/x/sys/unix"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

type sshCredential struct {
	Key string `json:"key"`
}
type configuration struct {
	Suspended  bool                     `json:"built_in_suspended"`
	Enabled    bool                     `json:"built_in_enabled"`
	Routes     map[string]string        `json:"routes"`
	BuiltIn    bool                     `json:"built_in"`
	ControlURL string                   `json:"control_url"`
	Directory  string                   `json:"directory"`
	SSH        map[string]sshCredential `json:"ssh"`
	Trusted    map[string]string        `json:"trusted"`
	Jumps      map[string]string        `json:"jumps"`
}

func (c configuration) tailscaleEnabled() bool {
	if c.Suspended {
		return false
	}
	if c.BuiltIn || c.Enabled {
		return true
	}
	for _, route := range c.Routes {
		if route == "builtin" {
			return true
		}
	}
	return false
}
func (c configuration) builtInFor(endpoint string) bool {
	if u, err := url.Parse(endpoint); err == nil && u.Path == "/" {
		u.Path = ""
		endpoint = u.String()
	}
	switch c.Routes[endpoint] {
	case "builtin":
		return true
	case "phone":
		return false
	}
	return c.BuiltIn
}

type interfaceInfo struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Flags     int      `json:"flags"`
	Addresses []string `json:"addresses"`
}
type hostPrompt struct {
	Host        string `json:"host"`
	Fingerprint string `json:"fingerprint"`
	Previous    string `json:"previous"`
	Type        string `json:"type"`
}

var state = struct {
	sync.Mutex
	config     configuration
	interfaces []interfaceInfo
	prompts    map[string]hostPrompt
	banners    map[string]string
	clients    map[string]*ssh.Client
	generation uint64
}{prompts: map[string]hostPrompt{}, banners: map[string]string{}, clients: map[string]*ssh.Client{}}
var tail = struct {
	sync.Mutex
	server *tsnet.Server
}{}

func init() {
	if runtime.GOOS == "android" {
		// java.net.NetworkInterface uses Android's permitted interface discovery path.
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
			state.Lock()
			snapshot := append([]interfaceInfo(nil), state.interfaces...)
			state.Unlock()
			result := make([]netmon.Interface, 0, len(snapshot))
			for _, entry := range snapshot {
				item := netmon.Interface{Interface: &net.Interface{Name: entry.Name, Index: entry.Index, MTU: entry.MTU, Flags: net.Flags(entry.Flags)}}
				for _, address := range entry.Addresses {
					_, prefix, err := net.ParseCIDR(address)
					if err == nil {
						item.AltAddrs = append(item.AltAddrs, prefix)
					}
				}
				result = append(result, item)
			}
			return result, nil
		})
	}
}

var configureMutex sync.Mutex

func configure(next configuration) error {
	configureMutex.Lock()
	defer configureMutex.Unlock()
	if next.Directory == "" {
		return errors.New("network state directory is missing")
	}
	if next.ControlURL != "" {
		u, err := url.Parse(next.ControlURL)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return errors.New("enter a complete control server HTTP(S) URL")
		}
	}
	if runtime.GOOS == "android" {
		logs := filepath.Join(next.Directory, "logs")
		if err := os.MkdirAll(logs, 0700); err != nil {
			return err
		}
		if err := os.Setenv("TS_LOGS_DIR", logs); err != nil {
			return err
		}
	}
	state.Lock()
	previous := state.config
	state.config = next
	state.generation++
	clients := state.clients
	state.clients = map[string]*ssh.Client{}
	state.prompts = map[string]hostPrompt{}
	state.banners = map[string]string{}
	state.Unlock()
	for _, client := range clients {
		client.Close()
	}
	if previous.tailscaleEnabled() != next.tailscaleEnabled() || previous.ControlURL != next.ControlURL || previous.Directory != next.Directory {
		tail.Lock()
		old := tail.server
		tail.server = nil
		tail.Unlock()
		if old != nil {
			old.Close()
		}
	}
	if next.tailscaleEnabled() {
		go func() { _, _ = tailServer() }()
	}
	return nil
}
func tailServer() (*tsnet.Server, error) {
	tail.Lock()
	defer tail.Unlock()
	if tail.server != nil {
		return tail.server, nil
	}
	state.Lock()
	config := state.config
	state.Unlock()
	if !config.tailscaleEnabled() {
		return nil, errors.New("built-in Tailscale is off")
	}
	control := config.ControlURL
	if control == "" {
		control = "https://controlplane.tailscale.com"
	}
	sum := sha256.Sum256([]byte(control))
	server := &tsnet.Server{Dir: filepath.Join(config.Directory, hex.EncodeToString(sum[:16])), Hostname: "rexling-android", ControlURL: config.ControlURL,
		Logf: func(string, ...any) {}, UserLogf: func(string, ...any) {}}
	if err := server.Start(); err != nil {
		server.Close()
		return nil, err
	}
	tail.server = server
	return server, nil
}
func networkDial(ctx context.Context, endpoint string, address string) (net.Conn, error) {
	state.Lock()
	builtIn := state.config.builtInFor(endpoint)
	state.Unlock()
	if builtIn {
		server, err := tailServer()
		if err != nil {
			return nil, err
		}
		return server.Dial(ctx, "tcp", address)
	}
	return (&net.Dialer{KeepAlive: 20 * time.Second}).DialContext(ctx, "tcp", address)
}

// Cancelling unfinished enrollment must not reset SSH clients or other routes.
func cancelLogin() error {
	tail.Lock()
	defer tail.Unlock()
	server := tail.server
	if server != nil {
		local, err := server.LocalClient()
		if err != nil {
			return err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		current, err := local.Status(ctx)
		if err != nil {
			cancel()
			return err
		}
		if current.BackendState == "Running" {
			cancel()
			return errors.New("Tailscale is already connected; sign-in was not cancelled")
		}
		// The local logout clears the pending interactive login before shutdown.
		err = local.Logout(ctx)
		cancel()
		if err != nil {
			return err
		}
	}
	state.Lock()
	state.config.Suspended = true
	state.Unlock()
	tail.server = nil
	if server != nil {
		server.Close()
	}
	return nil
}
func hostIdentity(u *url.URL) string {
	port := u.Port()
	if port == "" {
		port = "22"
	}
	return net.JoinHostPort(strings.ToLower(u.Hostname()), port)
}

// Keep Kotlin's endpoint spelling for configuration lookups; url.URL.String
// can re-escape userinfo. Treat a root slash like the saved root endpoint.
func sshEndpoint(endpoint string, u *url.URL) string {
	if u.Path == "/" && u.RawPath == "" && !u.ForceQuery && u.RawQuery == "" && u.Fragment == "" {
		return strings.TrimSuffix(endpoint, "/")
	}
	return endpoint
}

func sshClient(ctx context.Context, endpoint string, u *url.URL) (*ssh.Client, error) {
	state.Lock()
	settings := state.config
	generation := state.generation
	state.Unlock()

	var jump *url.URL
	if jumpEndpoint, ok := settings.Jumps[endpoint]; ok {
		var err error
		jump, err = url.Parse(jumpEndpoint)
		if err != nil || jump.Scheme != "ssh" || jump.Hostname() == "" || jump.User.Username() == "" || jump.ForceQuery || jump.RawQuery != "" || jump.Fragment != "" {
			return nil, errors.New("invalid saved SSH jump endpoint")
		}
		if _, hasPassword := jump.User.Password(); hasPassword {
			return nil, errors.New("invalid saved SSH jump endpoint")
		}
		if hostIdentity(u) == hostIdentity(jump) {
			return nil, errors.New("an SSH server cannot jump through itself")
		}
		jumpEndpoint = sshEndpoint(jumpEndpoint, jump)
		if _, chained := settings.Jumps[jumpEndpoint]; chained {
			return nil, errors.New("the SSH jump must be a direct server")
		}
		if settings.SSH[jumpEndpoint].Key == "" {
			return nil, errors.New("assign an SSH key to the saved jump server")
		}
	}
	return sshClientFor(ctx, endpoint, u, jump, settings, generation)
}

func sshClientFor(ctx context.Context, endpoint string, u, jump *url.URL, settings configuration, generation uint64) (*ssh.Client, error) {
	state.Lock()
	if generation != state.generation {
		state.Unlock()
		return nil, errors.New("SSH settings changed")
	}
	cached := state.clients[endpoint]
	state.Unlock()
	if cached != nil {
		return cached, nil
	}
	user := ""
	if u.User != nil {
		user = u.User.Username()
	}
	if user == "" {
		return nil, errors.New("include the SSH username, such as ssh://alice@host")
	}
	config := &ssh.ClientConfig{User: user, Timeout: 20 * time.Second}
	identity := hostIdentity(u)
	config.HostKeyCallback = func(_ string, _ net.Addr, key ssh.PublicKey) error {
		fingerprint := ssh.FingerprintSHA256(key)
		state.Lock()
		defer state.Unlock()
		if generation != state.generation {
			return errors.New("SSH settings changed")
		}
		trusted := settings.Trusted[identity]
		if trusted == fingerprint {
			delete(state.prompts, identity)
			return nil
		}
		state.prompts[identity] = hostPrompt{Host: identity, Fingerprint: fingerprint, Previous: trusted, Type: key.Type()}
		return errors.New("review the SSH host fingerprint in Rexling")
	}
	config.BannerCallback = func(message string) error {
		state.Lock()
		defer state.Unlock()
		if generation != state.generation {
			return errors.New("SSH settings changed")
		}
		state.banners[endpoint] = message
		return nil
	}
	if credential := settings.SSH[endpoint]; credential.Key != "" {
		signer, err := ssh.ParsePrivateKey([]byte(credential.Key))
		if err != nil {
			return nil, errors.New("could not read the saved SSH key")
		}
		config.Auth = []ssh.AuthMethod{ssh.PublicKeys(signer)}
	}
	var connection net.Conn
	var err error
	if jump == nil {
		connection, err = networkDial(ctx, endpoint, identity)
	} else {
		jumpEndpoint := sshEndpoint(settings.Jumps[endpoint], jump)
		firstHop, err := sshClientFor(ctx, jumpEndpoint, jump, nil, settings, generation)
		if err != nil {
			return nil, fmt.Errorf("SSH jump %s: %w", hostIdentity(jump), err)
		}
		connection, err = firstHop.DialContext(ctx, "tcp", identity)
		if err != nil {
			return nil, fmt.Errorf("SSH jump %s could not forward to %s: %w", hostIdentity(jump), identity, err)
		}
	}
	if err != nil {
		return nil, err
	}
	client, err := sshHandshake(ctx, connection, identity, config)
	if err != nil {
		return nil, fmt.Errorf("SSH login failed: %w", err)
	}
	state.Lock()
	if generation != state.generation {
		state.Unlock()
		client.Close()
		return nil, errors.New("SSH settings changed")
	}
	if existing := state.clients[endpoint]; existing != nil {
		state.Unlock()
		client.Close()
		return existing, nil
	}
	state.clients[endpoint] = client
	delete(state.banners, endpoint)
	state.Unlock()
	go func() {
		client.Wait()
		forgetSSHClient(endpoint, client)
	}()
	return client, nil
}

func sshHandshake(ctx context.Context, connection net.Conn, identity string, config *ssh.ClientConfig) (*ssh.Client, error) {
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	type result struct {
		client *ssh.Client
		err    error
	}
	// SSH forwarding channels do not implement deadlines. Bound the caller's
	// wait even if the peer does not promptly acknowledge the tunnel close.
	done := make(chan result)
	go func() {
		transport, channels, requests, err := ssh.NewClientConn(connection, identity, config)
		var client *ssh.Client
		if err == nil {
			client = ssh.NewClient(transport, channels, requests)
		}
		select {
		case done <- result{client, err}:
		case <-ctx.Done():
		}
	}()
	select {
	case r := <-done:
		if ctx.Err() == nil {
			return r.client, r.err
		}
	case <-ctx.Done():
	}
	// Only close this connection (the individual tunnel for a jumped target).
	go connection.Close()
	return nil, ctx.Err()
}

func forgetSSHClient(endpoint string, client *ssh.Client) {
	state.Lock()
	if state.clients[endpoint] == client {
		delete(state.clients, endpoint)
	}
	state.Unlock()
}

func rexSocket(ctx context.Context, client *ssh.Client, u *url.URL) (string, error) {
	if u.Path != "" && u.Path != "/" {
		return u.Path, nil
	}
	session, err := client.NewSession()
	if err != nil {
		return "", err
	}
	defer session.Close()
	stop := context.AfterFunc(ctx, func() { session.Close() })
	defer stop()
	// Match Rex's CLI discovery. No user text is interpolated into the command.
	output, err := session.Output(`sh -c 'if command -v rex >/dev/null 2>&1; then r=rex; elif test -x "$HOME/.local/bin/rex"; then r="$HOME/.local/bin/rex"; else echo "Rex is not installed on this SSH host" >&2; exit 127; fi; case "${REX_AUTOSTART-}" in ""|1|t|T|true|TRUE|True) "$r" server start --json;; *) "$r" server status --json;; esac'`)
	if err != nil {
		return "", errors.New("could not find or start Rex over SSH; install Rex on the host")
	}
	var status struct {
		Endpoint string `json:"endpoint"`
	}
	if json.Unmarshal(output, &status) != nil {
		return "", errors.New("Rex returned an invalid server status")
	}
	remote, err := url.Parse(status.Endpoint)
	if err != nil || remote.Scheme != "unix" || remote.Path == "" {
		return "", errors.New("this SSH host does not expose a Unix-socket Rex server")
	}
	return remote.Path, nil
}
func dial(ctx context.Context, endpoint string) (net.Conn, error) {
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, err
	}
	if u.Scheme == "ssh" {
		endpoint = sshEndpoint(endpoint, u)
		client, err := sshClient(ctx, endpoint, u)
		if err != nil {
			return nil, err
		}
		path, err := rexSocket(ctx, client, u)
		if err == nil {
			var conn net.Conn
			conn, err = client.DialContext(ctx, "unix", path)
			if err == nil {
				return conn, nil
			}
		}
		forgetSSHClient(endpoint, client)
		client.Close()
		return nil, err
	}
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" || u.Scheme == "wss" {
			port = "443"
		} else {
			port = "80"
		}
	}
	return networkDial(ctx, endpoint, net.JoinHostPort(u.Hostname(), port))
}
func status() map[string]any {
	state.Lock()
	builtIn := state.config.tailscaleEnabled()
	prompts := make([]hostPrompt, 0, len(state.prompts))
	for _, p := range state.prompts {
		prompts = append(prompts, p)
	}
	banners := make(map[string]string, len(state.banners))
	for k, v := range state.banners {
		banners[k] = v
	}
	state.Unlock()
	value := map[string]any{"built_in": builtIn, "hosts": prompts, "banners": banners}
	if builtIn {
		server, err := tailServer()
		if err != nil {
			value["error"] = err.Error()
			return value
		}
		local, err := server.LocalClient()
		if err != nil {
			value["error"] = err.Error()
			return value
		}
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		current, err := local.Status(ctx)
		if err != nil {
			value["error"] = err.Error()
		} else {
			value["tailscale"] = current
		}
	}
	return value
}

func importSSHKey(text, passphrase string) (map[string]any, error) {
	var key any
	var err error
	if passphrase == "" {
		key, err = ssh.ParseRawPrivateKey([]byte(text))
	} else {
		key, err = ssh.ParseRawPrivateKeyWithPassphrase([]byte(text), []byte(passphrase))
	}
	if err != nil {
		return nil, err
	}
	block, err := ssh.MarshalPrivateKey(key, "")
	if err != nil {
		return nil, err
	}
	signer, err := ssh.NewSignerFromKey(key)
	if err != nil {
		return nil, err
	}
	return map[string]any{"key": string(pem.EncodeToMemory(block)), "fingerprint": ssh.FingerprintSHA256(signer.PublicKey()), "kind": signer.PublicKey().Type()}, nil
}

//export rexnet_command
func rexnet_command(raw *C.char) *C.char {
	var request struct {
		Type       string          `json:"type"`
		Config     configuration   `json:"config"`
		Interfaces []interfaceInfo `json:"interfaces"`
		Key        string          `json:"key"`
		Passphrase string          `json:"passphrase"`
	}
	var result any = map[string]any{}
	err := json.Unmarshal([]byte(C.GoString(raw)), &request)
	if err == nil {
		switch request.Type {
		case "configure":
			err = configure(request.Config)
		case "interfaces":
			state.Lock()
			state.interfaces = request.Interfaces
			state.Unlock()
		case "status":
			result = status()
		case "login":
			var server *tsnet.Server
			server, err = tailServer()
			if err == nil {
				local, e := server.LocalClient()
				err = e
				if err == nil {
					ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
					err = local.StartLoginInteractive(ctx)
					cancel()
				}
			}
		case "cancel_login":
			err = cancelLogin()
		case "import_key":
			result, err = importSSHKey(request.Key, request.Passphrase)
		default:
			err = errors.New("unknown network command")
		}
	}
	if err != nil {
		result = map[string]any{"error": err.Error()}
	}
	bytes, _ := json.Marshal(result)
	return C.CString(string(bytes))
}

//export rexnet_dial
func rexnet_dial(raw *C.char, milliseconds C.int, errorOut **C.char) C.int {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(milliseconds)*time.Millisecond)
	defer cancel()
	connection, err := dial(ctx, C.GoString(raw))
	if err != nil {
		*errorOut = C.CString(err.Error())
		return -1
	}
	pair, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		connection.Close()
		*errorOut = C.CString(err.Error())
		return -1
	}
	unix.CloseOnExec(pair[0])
	unix.CloseOnExec(pair[1])
	file := os.NewFile(uintptr(pair[1]), "rexnet-relay")
	local, err := net.FileConn(file)
	file.Close()
	if err != nil {
		unix.Close(pair[0])
		connection.Close()
		*errorOut = C.CString(err.Error())
		return -1
	}
	go func() {
		defer local.Close()
		defer connection.Close()
		done := make(chan struct{})
		go func() { io.Copy(connection, local); connection.Close(); close(done) }()
		io.Copy(local, connection)
		local.Close()
		<-done
	}()
	return C.int(pair[0])
}

//export rexnet_free
func rexnet_free(pointer unsafe.Pointer) { C.free(pointer) }
func main()                              {}
