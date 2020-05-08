
## AWS Machine

Launched an EC2 instance in `us-east-1` (so Virginia, should be close enough to where PushShift is hosted, and renewables-powered), with AMI `ubuntu/images/hvm-ssd/ubuntu-bionic-18.04-amd64-server-20200112 (ami-07ebfd5b3428b6f4d)`.


### Installing Java and Clojure:

```
sudo apt-get update
sudo apt-get -y install tree jq curl git-core openjdk-8-jdk maven rlwrap
sudo apt-get -y install python3-venv python3-pip xdg-utils


sudo update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-8-openjdk-amd64/bin/java 1000
curl -O https://download.clojure.org/install/linux-install-1.10.1.536.sh
chmod +x linux-install-1.10.1.536.sh
sudo ./linux-install-1.10.1.536.sh
```

### Installing Python deps

```
ubuntu@ip-172-31-28-231:~$ wget https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh

ubuntu@ip-172-31-28-231:~$ sha256sum Miniconda3-latest-Linux-x86_64.sh
957d2f0f0701c3d1335e3b39f235d197837ad69a944fa6f5d8ad2c686b69df3b  Miniconda3-latest-Linux-x86_64.sh

ubuntu@ip-172-31-28-231:~$ conda create -n discussion_gems_condaenv python=3.7

ubuntu@ip-172-31-28-231:~$ conda activate discussion_gems_condaenv

ubuntu@ip-172-31-28-231:~$ conda install -c conda-forge pymc3
```


```
ubuntu@ip-172-31-28-231:~$ pip install -U sentence-transformers
```

### Downloading PushShift data

```
ubuntu@ip-172-31-40-156:~$ mkdir raw-data
ubuntu@ip-172-31-40-156:~$ cd raw-data
ubuntu@ip-172-31-40-156:~$ mkdir -p pushshift/reddit/comments
ubuntu@ip-172-31-40-156:~$ cd pushshift/reddit/comments/
ubuntu@ip-172-31-40-156:~/pushshift/reddit/comments$ wget https://files.pushshift.io/reddit/comments/RC_2019-09.zst

RC_2019-09.zst                                   5%[====>                                                                                              ] 845.66M  10.2MB/s    eta 20m 23s

ubuntu@ip-172-31-40-156:~$ mkdir ../submissions/
ubuntu@ip-172-31-40-156:~$ cd ../submissions/
ubuntu@ip-172-31-40-156:~/pushshift/reddit/submissions$ wget https://files.pushshift.io/reddit/submissions/RS_2019-08.zst https://files.pushshift.io/reddit/submissions/RS_2018-09.xz https://files.pushshift.io/reddit/submissions/RS_2017-09.bz2

```


### Setting up the project's code

```
ubuntu@ip-172-31-40-156:~$ git clone https://github.com/vvvvalvalval/discussion-gems.git

ubuntu@ip-172-31-40-156:~$ cd discussion-gems/
ubuntu@ip-172-31-40-156:~/discussion-gems$ touch ../repl-out.txt
```




## Remote REPL


### Start an nREPL server:

```
ubuntu@ip-172-31-40-156:~/discussion-gems$ nohup clojure -A:dev:nREPL -J-Xms3g -J-Xmx3g -J-XX:+UseG1GC -m nrepl.cmdline --port 8888  --middleware "[sc.nrepl.middleware/wrap-letsc]" </dev/null >>../repl-out.txt 2>&1 &
```

Then start an SSH tunnel on my dev laptop:

```
$ ssh -N -L 8888:localhost:8888 -i ~/.ssh/aws-vvv-perso-keypair.pem ubuntu@ec2-54-224-202-159.compute-1.amazonaws.com
```

Which then enables to connect any nREPL client, e.g:

```
$ clj -Sdeps '{:deps {nrepl {:mvn/version "0.5.0"}}}' -m nrepl.cmdline --connect --host localhost --port 8888
```


### Monitoring the nREPL server process

```
ubuntu@ip-172-31-40-156:~/discussion-gems$ tail -f ../repl-out.txt    ## Reading the REPL output in real-time. Can also use the `less` command.
[WARNING] No nREPL middleware descriptor in metadata of #'sc.nrepl.middleware/wrap-letsc, see nrepl.middleware/set-descriptor!
nREPL server started on port 8888 on host localhost - nrepl://localhost:8888

ubuntu@ip-172-31-40-156:~/discussion-gems$ jps    ## to find the nREPL process's id
19049 main  ## <-- it's this one, kill it with command `$ kill 19049`
19180 Jps

ubuntu@ip-172-31-40-156:~/discussion-gems$ df    ## how much disk space is left?
top - 14:55:26 up  1:20,  3 users,  load average: 0.12, 0.18, 0.18
Tasks: 105 total,   1 running,  64 sleeping,   0 stopped,   0 zombie
%Cpu(s):  0.0 us,  0.2 sy,  0.0 ni, 99.8 id,  0.0 wa,  0.0 hi,  0.0 si,  0.0 st
KiB Mem :  4037948 total,   242724 free,   455796 used,  3339428 buff/cache
KiB Swap:        0 total,        0 free,        0 used.  3320564 avail Mem

  PID USER      PR  NI    VIRT    RES    SHR S  %CPU %MEM     TIME+ COMMAND
19049 ubuntu    20   0 5850720 354436  27592 S   0.0  8.8   0:07.59 java
 1022 root      20   0  187676  12404   4480 S   0.0  0.3   0:00.07 unattended-upgr
 1406 root      20   0  848316  11768   2840 S   0.0  0.3   0:01.02 snapd
  939 root      20   0  170824  10324   2560 S   0.0  0.3   0:00.08 networkd-dispat
19081 root      20   0  107984   6984   5976 S   0.0  0.2   0:00.00 sshd

ubuntu@ip-172-31-40-156:~/discussion-gems$ top    ## CPU / memory usage. Maj+M to order by memory usage.
top - 14:55:26 up  1:20,  3 users,  load average: 0.12, 0.18, 0.18
Tasks: 105 total,   1 running,  64 sleeping,   0 stopped,   0 zombie
%Cpu(s):  0.0 us,  0.2 sy,  0.0 ni, 99.8 id,  0.0 wa,  0.0 hi,  0.0 si,  0.0 st
KiB Mem :  4037948 total,   242724 free,   455796 used,  3339428 buff/cache
KiB Swap:        0 total,        0 free,        0 used.  3320564 avail Mem

  PID USER      PR  NI    VIRT    RES    SHR S  %CPU %MEM     TIME+ COMMAND
19049 ubuntu    20   0 5850720 354436  27592 S   0.0  8.8   0:07.59 java
 1022 root      20   0  187676  12404   4480 S   0.0  0.3   0:00.07 unattended-upgr
 1406 root      20   0  848316  11768   2840 S   0.0  0.3   0:01.02 snapd
  939 root      20   0  170824  10324   2560 S   0.0  0.3   0:00.08 networkd-dispat
19081 root      20   0  107984   6984   5976 S   0.0  0.2   0:00.00 sshd
```




## Dataset construction

```
ubuntu@ip-172-31-40 cat raw-data/pushshift/reddit/comments/RC_2019-08.zst | zstd -cdq | grep '"france"' | jq -c 'select (.subreddit == "france")' | gzip -c >> datasets/reddit-france/comments/RC_2019-08.jsonl.gz
```

