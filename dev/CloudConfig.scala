object CloudConfig {

  def render(
      hostname: String,
      domainname: String,
      ipAddress: String,
      dns1: String,
      dns2: String,
      username: String,
      password: String,
      sshKey: String,
      timezone: String = "Europe/London"
  ): String =
    s"""#cloud-config
       |# password: passw0rd
       |# chpasswd: { expire: False }
       |# ssh_pwauth: True
       |# Install my public ssh key to the first user-defined user configured
       |# in cloud.cfg in the template (which is centos for CentOS cloud images)
       |preserve_hostname: False
       |hostname: $hostname
       |fqdn: $hostname.$domainname
       |
       |# Users
       |users:
       |    - default
       |    - name: $username
       |      groups:
       |        - wheel
       |      shell: /bin/bash
       |      oock_passwd: false
       |      sudo:
       |        - ALL=(ALL) NOPASSWD:ALL
       |      ssh-authorized-keys:
       |        - $sshKey
       |
       |chpasswd:
       |  list:
       |    - "$username:$password"
       |  expire: false
       |
       |# Configure where output will go
       |output:
       |  all: ">> /var/log/cloud-init.log"
       |
       |# configure interaction with ssh server
       |ssh_genkeytypes: ['ed25519', 'rsa']
       |
       |# Install my public ssh key to the first user-defined user configured
       |# in cloud.cfg in the template (which is centos for CentOS cloud images)
       |ssh_authorized_keys:
       |  - $sshKey
       |
       |# set timezone for VM
       |timezone: $timezone
       |
       |# Enter host in /etc/hosts
       |write_files:
       |  - path: /etc/hosts
       |    content: |
       |      127.0.0.1 localhost localhost.localdomain localhost4 localhost4.localdomain4
       |      ::1 localhost localhost.localdomain localhost6 localhost6.localdomain6
       |      $ipAddress $hostname.$domainname $hostname
       |  - path: /etc/resolv.conf
       |    content: |
       |      nameserver $dns1
       |      nameserver $dns2
       |      search $domainname
       |
       |# Remove cloud-init
       |runcmd:
       |  - echo "Hi there"
       |
       |# EOF
       |""".stripMargin
}
