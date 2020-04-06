
## AWS Machine

Launched an EC2 instance in `us-east-1` (so Virginia, should be close enough to where PushShift is hosted, and renewables-powered), with AMI `ubuntu/images/hvm-ssd/ubuntu-bionic-18.04-amd64-server-20200112 (ami-07ebfd5b3428b6f4d)`.


### Installing Java and Clojure:

```
sudo apt-get update
sudo apt-get -y install curl git-core openjdk-8-jdk maven rlwrap
curl -O https://download.clojure.org/install/linux-install-1.10.1.536.sh
chmod +x linux-install-1.10.1.536.sh
sudo ./linux-install-1.10.1.536.sh
```



### Downloading PushShift data

```
ubuntu@ip-172-31-40-156:~$ mkdir raw-data
ubuntu@ip-172-31-40-156:~$ mkdir -p pushshift/reddit/comments
ubuntu@ip-172-31-40-156:~$ cd pushshift/reddit/comments/
ubuntu@ip-172-31-40-156:~/pushshift/reddit/comments$ wget https://files.pushshift.io/reddit/comments/RC_2019-09.zst

RC_2019-09.zst                                   5%[====>                                                                                              ] 845.66M  10.2MB/s    eta 20m 23s

```





