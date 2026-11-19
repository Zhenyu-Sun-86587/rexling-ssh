package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// The loopback jump fixture supports only TCP forwarding, not sessions or Rex sockets.
func testJump(t *testing.T, forward func(ssh.Conn, string, ssh.Channel)) (string, string, string) {
	t.Helper()
	return testSSHWithChannels(t, true, func(server ssh.Conn, channel ssh.NewChannel) {
		var payload struct {
			Host       string
			Port       uint32
			Origin     string
			OriginPort uint32
		}
		if channel.ChannelType() != "direct-tcpip" || ssh.Unmarshal(channel.ExtraData(), &payload) != nil {
			channel.Reject(ssh.UnknownChannelType, "jump only supports TCP forwarding")
			return
		}
		stream, requests, err := channel.Accept()
		if err != nil {
			return
		}
		go ssh.DiscardRequests(requests)
		go func() {
			defer stream.Close()
			forward(server, net.JoinHostPort(payload.Host, fmt.Sprint(payload.Port)), stream)
		}()
	})
}

func relayTestTarget(stream ssh.Channel, addr string) {
	remote, err := net.DialTimeout("tcp", addr, time.Second)
	if err != nil {
		return
	}
	defer remote.Close()
	go func() { io.Copy(remote, stream); remote.Close() }()
	io.Copy(stream, remote)
}

func TestSSHJumpTrustAuthenticationAndStream(t *testing.T) {
	target, targetKey, targetFingerprint := testSSH(t, true)
	targetURL, _ := url.Parse(target)
	localAddr := targetURL.Host
	targetURL.Host = net.JoinHostPort("rex-target.invalid", targetURL.Port())
	// Kotlin's canonical key preserves '!', which Go's URL.String would escape.
	target = "ssh://tester!@" + targetURL.Host
	targetHost := hostIdentity(targetURL)
	jump, jumpKey, jumpFingerprint := testJump(t, func(_ ssh.Conn, addr string, stream ssh.Channel) {
		if addr == targetHost {
			relayTestTarget(stream, localAddr)
		}
	})
	jumpURL, _ := url.Parse(jump)
	jumpHost := hostIdentity(jumpURL)
	config := configuration{
		Suspended: true, // If the target route is used locally, the dial fails instead of using the phone route.
		Routes:    map[string]string{target: "builtin", jump: "phone"},
		SSH:       map[string]sshCredential{target: {Key: targetKey}, jump: {Key: jumpKey}},
	}
	if err := json.Unmarshal(fmt.Appendf(nil, `{"jumps":{%q:%q}}`, target, jump), &config); err != nil {
		t.Fatal(err)
	}
	defer reset(t, configuration{})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for _, check := range []struct {
		trusted     map[string]string
		host        string
		fingerprint string
	}{
		{nil, jumpHost, jumpFingerprint},
		{map[string]string{jumpHost: jumpFingerprint}, targetHost, targetFingerprint},
	} {
		config.Trusted = check.trusted
		reset(t, config)
		if conn, err := dial(ctx, target+"/"); err == nil {
			conn.Close()
			t.Fatal("untrusted host accepted")
		}
		prompts := status()["hosts"].([]hostPrompt)
		if len(prompts) != 1 || prompts[0].Host != check.host || prompts[0].Fingerprint != check.fingerprint {
			t.Fatalf("want trust prompt for %s, got %+v", check.host, prompts)
		}
	}
	config.Trusted = map[string]string{jumpHost: jumpFingerprint, targetHost: targetFingerprint}
	reset(t, config)
	conn, err := dial(ctx, target+"/")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	if _, err := io.WriteString(conn, "hello via jump"); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, len("hello via jump"))
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "hello via jump" {
		t.Fatalf("Rex stream: %q %v", buf, err)
	}
}

func TestSSHJumpDoesNotFallBackToDirect(t *testing.T) {
	target, targetKey, targetFingerprint := testSSH(t, true)
	jump, jumpKey, jumpFingerprint := testSSH(t, true) // Refuses TCP forwarding.
	targetURL, _ := url.Parse(target)
	jumpURL, _ := url.Parse(jump)
	config := configuration{
		SSH:     map[string]sshCredential{target: {Key: targetKey}, jump: {Key: jumpKey}},
		Trusted: map[string]string{hostIdentity(targetURL): targetFingerprint, hostIdentity(jumpURL): jumpFingerprint},
	}
	defer reset(t, configuration{})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	reset(t, config)
	conn, err := dial(ctx, target)
	if err != nil {
		t.Fatalf("control: target must be reachable directly: %v", err)
	}
	conn.Close()
	for _, tc := range []struct {
		name  string
		jumps map[string]string
		want  string
	}{
		{"self", map[string]string{target: target + "/another.sock"}, "cannot jump through itself"},
		{"chain", map[string]string{target: jump, jump: "ssh://tester@elsewhere"}, "must be a direct server"},
		{"invalid", map[string]string{target: ""}, "invalid saved SSH jump"},
		{"forwarding refused", map[string]string{target: jump}, "could not forward"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			config.Jumps = tc.jumps
			reset(t, config)
			conn, err := dial(ctx, target)
			if err == nil {
				conn.Close()
				t.Fatal("unexpected successful dial")
			}
			if !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("want %q, got %v", tc.want, err)
			}
		})
	}
}

func TestSSHJumpCancellationAndCache(t *testing.T) {
	target, targetKey, targetFingerprint := testSSH(t, true)
	targetURL, _ := url.Parse(target)
	stalled := "ssh://tester@stalled.invalid"
	firstHops := make(chan ssh.Conn, 3)
	tunnelClosed := make(chan struct{}, 1)
	jump, jumpKey, jumpFingerprint := testJump(t, func(server ssh.Conn, addr string, stream ssh.Channel) {
		switch addr {
		case "stalled.invalid:22":
			reader := bufio.NewReader(stream)
			if _, err := reader.ReadString('\n'); err != nil {
				return
			}
			firstHops <- server
			io.Copy(io.Discard, reader) // Never send the SSH server identification.
			tunnelClosed <- struct{}{}
		case hostIdentity(targetURL):
			firstHops <- server
			relayTestTarget(stream, addr)
		}
	})
	jumpURL, _ := url.Parse(jump)
	reset(t, configuration{
		SSH:     map[string]sshCredential{target: {Key: targetKey}, jump: {Key: jumpKey}},
		Trusted: map[string]string{hostIdentity(targetURL): targetFingerprint, hostIdentity(jumpURL): jumpFingerprint},
		Jumps:   map[string]string{target: jump, stalled: jump},
	})
	defer reset(t, configuration{})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	readHop := func() ssh.Conn {
		t.Helper()
		select {
		case hop := <-firstHops:
			return hop
		case <-ctx.Done():
			t.Fatal("no forwarded connection reached the jump")
			return nil
		}
	}
	short, cancelShort := context.WithCancel(ctx)
	defer cancelShort()
	result := make(chan error, 1)
	go func() {
		conn, err := dial(short, stalled)
		if conn != nil {
			conn.Close()
		}
		result <- err
	}()
	firstHop := readHop()
	cancelShort()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("want cancellation, got %v", err)
		}
	case <-ctx.Done():
		t.Fatal("stalled nested handshake did not cancel")
	}
	select {
	case <-tunnelClosed:
	case <-ctx.Done():
		t.Fatal("cancelled target tunnel was not closed")
	}
	dialTarget := func() ssh.Conn {
		t.Helper()
		conn, err := dial(ctx, target)
		if err != nil {
			t.Fatal(err)
		}
		conn.Close()
		return readHop()
	}
	if dialTarget() != firstHop {
		t.Fatal("target cancellation closed the shared first hop")
	}
	firstHop.Close()
	for {
		state.Lock()
		evicted := state.clients[jump] == nil && state.clients[target] == nil
		state.Unlock()
		if evicted {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("closed SSH clients remained cached")
		case <-time.After(time.Millisecond):
		}
	}
	if dialTarget() == firstHop {
		t.Fatal("reused a closed first hop")
	}
}
