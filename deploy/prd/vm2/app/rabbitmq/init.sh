# 该命令以节点1为例
docker exec -it scaffold-ai-assistant-rabbitmq02 /bin/bash

#ram节点加入集群

rabbitmqctl stop_app
rabbitmqctl reset
rabbitmqctl join_cluster --ram scaffold-ai-assistant-rabbitmq01@scaffold-ai-assistant-rabbitmq01
rabbitmqctl start_app