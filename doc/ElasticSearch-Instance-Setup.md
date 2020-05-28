# ElasticSearch Instance Setup

This document records the installation and configuration steps for setting up a VM with a running ElasticSearch node.

NOTE: that's a single ES node, not a cluster, which is sufficient for our purposes at the time of writing. (Val, 27 May 2020)

## AWS Instance

Created an EC2 instance with characteristics:

* Instance type: `t2.medium`, hopefully will be enough to take the load
* 200 Gb of gp2 storage
* with newly-created Security Group `discussion-gems-es-sg`, allowing SSH access and TCP:9200 access from other SG `discussion-gems-sg`
* with Ubuntu AMI 18.04 LTS `ubuntu/images/hvm-ssd/ubuntu-bionic-18.04-amd64-server-20200408 (ami-085925f297f89fce1)`


## Ubuntu Setup

### Installing ElasticSearch

Followed the instructions from here: https://www.elastic.co/guide/en/elasticsearch/reference/7.7/targz.html#install-linux


```
ubuntu@ip-172-31-70-82:~$ wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.7.0-linux-x86_64.tar.gz
ubuntu@ip-172-31-70-82:~$ wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.7.0-linux-x86_64.tar.gz.sha512
ubuntu@ip-172-31-70-82:~$ shasum -a 512 -c elasticsearch-7.7.0-linux-x86_64.tar.gz.sha512
elasticsearch-7.7.0-linux-x86_64.tar.gz: OK
ubuntu@ip-172-31-70-82:~$ tar -xzf elasticsearch-7.7.0-linux-x86_64.tar.gz
```

### Configuring ElasticSearch

#### Java SDK location

Added to `~/.bash_profile`:

```
ES_HOME="/home/ubuntu/elasticsearch-7.7.0"
JAVA_HOME="$ES_HOME/jdk"
```

#### Data paths

Goal: store data outside of the ES installation folder, to prevent overwrites when upgrading.

Created data and logs directories:

```
ubuntu@ip-172-31-70-82:~$ mkdir es_data
ubuntu@ip-172-31-70-82:~$ mkdir es_data/data
ubuntu@ip-172-31-70-82:~$ mkdir es_data/logs
```

Then set the following in `$ES_HOME/config/elasticsearch.yml`:

```
path.data: /home/ubuntu/es_data/data
path.logs: /home/ubuntu/es_data/logs
```

#### JVM Heap Size

I set the JVM heap size to half of the instance memory, by adding this to `$ES_HOME/config/jvm.options`:

```
-Xms2g
-Xmx2g
```

#### Bootstrap checks

Goal: being able to run ElasticSearch queries from other machines.

See: https://www.elastic.co/guide/en/elasticsearch/reference/7.7/bootstrap-checks.html#single-node-discovery

Set the following to `$ES_HOME/config/elasticsearch.yml`:

```
## NOTE added by Val
network.host: _site_
discovery.type: single-node
```



## Starting ElasticSearch

Running ElasticSearch as a daemon process:

```
ubuntu@ip-172-31-70-82:~/elasticsearch-7.7.0$ cd $ES_HOME
ubuntu@ip-172-31-70-82:~/elasticsearch-7.7.0$ ./bin/elasticsearch --daemonize
```

### Checkpoint

Start an SSH tunnel from your local machine:

```
$ ssh -N -L 9200:172.31.70.82:9200  -i ~/.ssh/aws-vvv-perso-keypair.pem ubuntu@ec2-3-227-208-34.compute-1.amazonaws.com
```

(The required IP address - in this case `172.31.70.82` - is the private IP of the EC2 instance.)

Then visit localhost:9200:

```
$ curl http://localhost:9200/
{
  "name" : "ip-172-31-70-82",
  "cluster_name" : "discussion-gems-es-cluster",
  "cluster_uuid" : "S3_OIYu1RomeQgTpZNiK5Q",
  "version" : {
    "number" : "7.7.0",
    "build_flavor" : "default",
    "build_type" : "tar",
    "build_hash" : "81a1e9eda8e6183f5237786246f6dced26a10eaf",
    "build_date" : "2020-05-12T02:01:37.602180Z",
    "build_snapshot" : false,
    "lucene_version" : "8.5.1",
    "minimum_wire_compatibility_version" : "6.8.0",
    "minimum_index_compatibility_version" : "6.0.0-beta1"
  },
  "tagline" : "You Know, for Search"
}
```
