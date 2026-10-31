// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 11: backups of the cluster's state. Needs
// k8s_cluster.scala and the Ceph object gateway (rgw_single_node.scala on tst0).
//
// Each control-plane node (tst30, tst31, tst32) gets /usr/local/sbin/orphera-etcd-backup
// and a systemd timer that runs it every hour (at :05, :25 and :45, so the three
// do not run together). A run does this:
//   1. takes an etcd snapshot with `etcdctl snapshot save`, run inside the node's
//      own etcd container (crictl exec), and checks it with `etcdutl snapshot status`
//      (a snapshot with no keys is refused);
//   2. packs /etc/kubernetes/pki (the cluster CAs and keys, without which a restored
//      etcd is of no use) into a tar file;
//   3. keeps both in /var/backups/etcd (the newest 48 of each) and uploads both to
//      the bucket `etcd-backups` on the lab's Ceph object gateway, under <node>/ (the
//      newest 168 of each kind per node are kept there, a week of hourly runs).
//
// The upload uses a native RGW user `etcd-backup` with its own S3 keys, made by
// `radosgw-admin` on tst0 and copied to the control-plane nodes as a root-only file
// (/etc/etcd-backup/s3.cred); it does not depend on Keystone, so the backups can be
// fetched when OpenStack is down. The object gateway speaks plain HTTP in this lab,
// so the PKI archives cross the network unencrypted: acceptable here, not elsewhere.
//
//   orphera-etcd-backup            take a backup now
//   orphera-etcd-backup list       list what the bucket holds
//   orphera-etcd-backup restore-test   restore the newest local snapshot into a
//                                  scratch data directory and check it, then remove it
//
// Restoring the cluster is deliberately not automated. In outline: stop the
// kube-apiserver and etcd static pods on every control-plane node (move the manifests
// out of /etc/kubernetes/manifests), move /var/lib/etcd aside, run
// `etcdutl snapshot restore <file> --data-dir /var/lib/etcd` with the member's name
// and peer URLs (see the etcd documentation, "Restoring a cluster"), put the
// manifests back, and restore /etc/kubernetes/pki from the archive if it was lost.
//
// The last stage runs a backup on every node, finds all three in the bucket and runs
// the restore test. The restore test proves a snapshot can be restored into a data
// directory; it does not start a second etcd.
//
// Not compiled where this was written; the first run on scala0 is the first compile.
object k8s_etcd_backup extends OrpheraClusterPlaybook:

  private val cephNode = "tst0"
  private val cpNodes = List("tst30", "tst31", "tst32")
  private val rgw = s"{{nodes.$cephNode.cluster_ip}}:8080"
  private val bucket = "etcd-backups"
  private val rgwUser = "etcd-backup"
  private val credSource = s"/etc/ceph/$rgwUser.s3"
  private val credDest = "/etc/etcd-backup/s3.cred"
  private val helper = "/usr/local/sbin/orphera-etcd-backup"

  private val rgwUserScript =
    s"""set -e
       |if ! cephadm shell -- radosgw-admin user info --uid=$rgwUser >/dev/null 2>&1; then
       |  cephadm shell -- radosgw-admin user create --uid=$rgwUser --display-name="etcd backups" >/dev/null
       |  echo "RGW user $rgwUser created"
       |fi
       |JSON=$$(cephadm shell -- radosgw-admin user info --uid=$rgwUser 2>/dev/null)
       |umask 077
       |printf '%s' "$$JSON" | python3 -c 'import sys,json; k=json.load(sys.stdin)["keys"][0]; print(k["access_key"]); print(k["secret_key"])' > $credSource
       |[ "$$(wc -l < $credSource)" -eq 2 ] || { echo "could not read the S3 keys of $rgwUser" >&2; exit 1; }
       |echo "S3 keys of $rgwUser written to $credSource"
       |""".stripMargin

  private val helperPython =
    """#!/usr/bin/python3
import datetime, glob, gzip, hashlib, hmac, http.client, json, os, re, shutil, socket, subprocess, sys, tarfile, time, urllib.parse

RGW = '@@RGW@@'
BUCKET = '@@BUCKET@@'
CRED = '@@CRED@@'
NODE = socket.gethostname().split('.')[0]
DIR = '/var/backups/etcd'
KEEP_LOCAL = 48
KEEP_REMOTE = 168
ETCD_ARGS = ['--endpoints=https://127.0.0.1:2379', '--cacert=/etc/kubernetes/pki/etcd/ca.crt',
             '--cert=/etc/kubernetes/pki/etcd/server.crt', '--key=/etc/kubernetes/pki/etcd/server.key']


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def run(cmd):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if p.returncode != 0:
        die('%s failed: %s' % (' '.join(cmd[:4]), (p.stderr or p.stdout).strip()[:400]))
    return p.stdout


def keys():
    lines = open(CRED).read().split()
    return lines[0], lines[1]


def s3(method, path, query='', body=b''):
    ak, sk = keys()
    amz = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
    date = amz[:8]
    phash = hashlib.sha256(body).hexdigest()
    signed = 'host;x-amz-content-sha256;x-amz-date'
    canon = '\n'.join([method, path, query, 'host:%s\nx-amz-content-sha256:%s\nx-amz-date:%s\n' % (RGW, phash, amz), signed, phash])
    scope = '%s/us-east-1/s3/aws4_request' % date
    sts = '\n'.join(['AWS4-HMAC-SHA256', amz, scope, hashlib.sha256(canon.encode()).hexdigest()])

    def mac(key, msg):
        return hmac.new(key, msg.encode(), hashlib.sha256).digest()

    k = mac(('AWS4' + sk).encode(), date)
    for part in ('us-east-1', 's3', 'aws4_request'):
        k = mac(k, part)
    sig = hmac.new(k, sts.encode(), hashlib.sha256).hexdigest()
    headers = {'x-amz-content-sha256': phash, 'x-amz-date': amz,
               'Authorization': 'AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s' % (ak, scope, signed, sig)}
    host, _, port = RGW.partition(':')
    c = http.client.HTTPConnection(host, int(port or 80), timeout=120)
    c.request(method, path + ('?' + query if query else ''), body=body if body else None, headers=headers)
    r = c.getresponse()
    data = r.read()
    c.close()
    return r.status, data


def ensure_bucket():
    s, b = s3('PUT', '/' + BUCKET)
    if s not in (200, 409):
        die('creating bucket %s: HTTP %s %r' % (BUCKET, s, b[:200]))


def remote_keys(prefix):
    out = []
    token = ''
    while True:
        q = 'list-type=2&prefix=' + urllib.parse.quote(prefix, safe='')
        if token:
            q = 'continuation-token=' + urllib.parse.quote(token, safe='') + '&' + q
        # the canonical query string has its parameters sorted by name
        q = '&'.join(sorted(q.split('&')))
        s, b = s3('GET', '/' + BUCKET, q)
        if s != 200:
            die('listing %s: HTTP %s %r' % (prefix, s, b[:200]))
        text = b.decode()
        out += re.findall(r'<Key>([^<]+)</Key>', text)
        m = re.search(r'<NextContinuationToken>([^<]+)</NextContinuationToken>', text)
        if '<IsTruncated>true</IsTruncated>' in text and m:
            token = m.group(1)
        else:
            return out


def put(name, path):
    data = open(path, 'rb').read()
    s, b = s3('PUT', '/%s/%s' % (BUCKET, name), body=data)
    if s != 200:
        die('uploading %s: HTTP %s %r' % (name, s, b[:200]))
    return len(data)


def etcd_container():
    ids = run(['crictl', 'ps', '--name', '^etcd$', '-q']).split()
    if not ids:
        die('no running etcd container on this node')
    return ids[0]


def snapshot_status(cid, path_in_container):
    for tool in (['etcdutl', 'snapshot', 'status'], ['etcdctl', 'snapshot', 'status']):
        p = subprocess.run(['crictl', 'exec', cid] + tool + [path_in_container, '-w', 'json'],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if p.returncode == 0:
            try:
                return json.loads(p.stdout)
            except ValueError:
                pass
    die('could not read the snapshot status')


def prune_local(pattern):
    files = sorted(glob.glob(os.path.join(DIR, pattern)))
    for f in files[:-KEEP_LOCAL]:
        os.remove(f)


def prune_remote(prefix_kind):
    ks = sorted(k for k in remote_keys(NODE + '/') if k.startswith('%s/%s-' % (NODE, prefix_kind)))
    for k in ks[:-KEEP_REMOTE]:
        s3('DELETE', '/%s/%s' % (BUCKET, k))


def backup():
    os.makedirs(DIR, exist_ok=True)
    cid = etcd_container()
    stamp = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
    inside = '/var/lib/etcd/orphera-snapshot.db'
    run(['crictl', 'exec', cid, 'etcdctl'] + ETCD_ARGS + ['snapshot', 'save', inside])
    st = snapshot_status(cid, inside)
    if not st.get('totalKey'):
        os.remove(inside)
        die('the snapshot holds no keys: %s' % st)
    snap = os.path.join(DIR, 'etcd-%s-%s.db' % (NODE, stamp))
    shutil.move(inside, snap)
    pki = os.path.join(DIR, 'pki-%s-%s.tar.gz' % (NODE, stamp))
    old = os.umask(0o077)
    with tarfile.open(pki, 'w:gz') as t:
        t.add('/etc/kubernetes/pki', arcname='pki')
    os.chmod(snap, 0o600)
    os.umask(old)
    ensure_bucket()
    n1 = put('%s/%s' % (NODE, os.path.basename(snap)), snap)
    n2 = put('%s/%s' % (NODE, os.path.basename(pki)), pki)
    prune_local('etcd-%s-*.db' % NODE)
    prune_local('pki-%s-*.tar.gz' % NODE)
    prune_remote('etcd')
    prune_remote('pki')
    print('%s: snapshot %s (%d keys, %d bytes) and pki archive (%d bytes) kept in %s and uploaded to %s/%s/' % (
        NODE, os.path.basename(snap), st['totalKey'], n1, n2, DIR, BUCKET, NODE))


def listing():
    ks = remote_keys('')
    for k in ks:
        print(k)
    print('%d objects in %s' % (len(ks), BUCKET))


def restore_test():
    snaps = sorted(glob.glob(os.path.join(DIR, 'etcd-%s-*.db' % NODE)))
    if not snaps:
        die('no local snapshot in ' + DIR)
    cid = etcd_container()
    inside = '/var/lib/etcd/orphera-restore-test.db'
    scratch = '/var/lib/etcd/orphera-restore-test'
    shutil.copy(snaps[-1], inside)
    shutil.rmtree(scratch, ignore_errors=True)
    try:
        out = subprocess.run(['crictl', 'exec', cid, 'etcdutl', 'snapshot', 'restore', inside, '--data-dir', scratch],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if out.returncode != 0:
            die('etcdutl snapshot restore failed: %s' % out.stdout.strip()[:400])
        if not os.path.isfile(os.path.join(scratch, 'member', 'snap', 'db')):
            die('the restored data directory has no member/snap/db')
        st = snapshot_status(cid, inside)
        print('restore test ok: %s restores into a data directory (%d keys, revision %s)' % (
            os.path.basename(snaps[-1]), st.get('totalKey', 0), st.get('revision')))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
        if os.path.exists(inside):
            os.remove(inside)


cmd = sys.argv[1] if len(sys.argv) > 1 else 'backup'
if cmd == 'backup':
    backup()
elif cmd == 'list':
    listing()
elif cmd == 'restore-test':
    restore_test()
else:
    die('usage: orphera-etcd-backup [backup | list | restore-test]')
"""

  private val helperText =
    helperPython
      .replace("@@RGW@@", rgw)
      .replace("@@BUCKET@@", bucket)
      .replace("@@CRED@@", credDest)

  private val prepareScript =
    """mkdir -p /etc/etcd-backup /var/backups/etcd
      |chmod 700 /etc/etcd-backup /var/backups/etcd
      |echo "directories ready"""".stripMargin

  private val installScript =
    s"""set -e
       |[ -s $credDest ] || { echo "$credDest is missing" >&2; exit 1; }
       |chmod 600 $credDest
       |cat > $helper <<'PYEOF'
       |$helperText
       |PYEOF
       |chmod 700 $helper
       |python3 -m py_compile $helper && rm -rf /usr/local/sbin/__pycache__
       |case "$$(hostname -s)" in tst30) M=5;; tst31) M=25;; *) M=45;; esac
       |cat > /etc/systemd/system/orphera-etcd-backup.service <<'EOF'
       |[Unit]
       |Description=etcd snapshot and PKI backup (Orphera)
       |
       |[Service]
       |Type=oneshot
       |ExecStart=$helper backup
       |EOF
       |cat > /etc/systemd/system/orphera-etcd-backup.timer <<EOF
       |[Unit]
       |Description=hourly etcd backup (Orphera)
       |
       |[Timer]
       |OnCalendar=*-*-* *:$$M:00
       |Persistent=true
       |
       |[Install]
       |WantedBy=timers.target
       |EOF
       |systemctl daemon-reload
       |systemctl enable --now orphera-etcd-backup.timer
       |systemctl list-timers orphera-etcd-backup.timer --no-pager
       |""".stripMargin

  private val firstRunScript = s"$helper backup"

  private val verifyScript =
    s"""set -e
       |$helper list | tee /tmp/etcd-backup-list.txt
       |for N in ${cpNodes.mkString(" ")}; do
       |  grep -q "^$$N/etcd-$$N-.*\\.db$$" /tmp/etcd-backup-list.txt || { echo "no snapshot of $$N in the bucket" >&2; exit 1; }
       |  grep -q "^$$N/pki-$$N-.*\\.tar\\.gz$$" /tmp/etcd-backup-list.txt || { echo "no PKI archive of $$N in the bucket" >&2; exit 1; }
       |done
       |rm -f /tmp/etcd-backup-list.txt
       |echo "the bucket holds a snapshot and a PKI archive of each control-plane node"
       |$helper restore-test
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-etcd-backup")(
      stage("rgw-user", cephNode)
        .task("create the RGW user and read its S3 keys")(
          Task.RunCommand(List("sh", "-c", rgwUserScript), timeoutSeconds = 180)
        )
        .build,
      stage("prepare-nodes", cpNodes*)
        .task("create the backup directories")(
          Task.RunCommand(List("sh", "-c", prepareScript))
        )
        .build,
      stage("send-credentials", cpNodes*)
        .task("copy the S3 keys from tst0")(
          Task
            .DistributeFile(cephNode, credSource, credDest, "root", "root", 384)
        )
        .build,
      stage("install-backup", cpNodes*)
        .task("install the helper and the hourly timer")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 120)
        )
        .build,
      stage("first-backup", cpNodes*)
        .task("take a backup now")(
          Task
            .RunCommand(List("sh", "-c", firstRunScript), timeoutSeconds = 300)
        )
        .build,
      stage("verify-backups", "tst30")
        .task("list the bucket and test a restore")(
          Task.RunCommand(List("sh", "-c", verifyScript), timeoutSeconds = 300)
        )
        .task("backups ready")(
          Task.Debug(
            s"etcd snapshots and PKI archives of tst30-tst32 are taken hourly and kept in $bucket on the Ceph object gateway " +
              s"($rgw); the newest local snapshot restores into a data directory."
          )
        )
        .build
    )
