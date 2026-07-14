-- 1、初始化数据库：创建nacos 外置数据库 scaffold-ai-assistant_nacos_dev 和脚手架业务数据库 scaffold-ai-assistant_dev
-- 2、创建用户，用户名：zmbdpdev 密码：Hf@173503494
-- 3、授予zmbdpdev用户特定权限
create database if not exists `scaffold-ai-assistant_nacos_dev` default character set utf8mb4 collate utf8mb4_general_ci;
create database if not exists `scaffold-ai-assistant_dev` default character set utf8mb4 collate utf8mb4_general_ci;
create database if not exists `scaffold-ai-assistant_xxljob_dev` default character set utf8mb4 collate utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_skywalking_dev` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

create user 'zmbdpdev'@'%' identified BY 'Hf@173503494';

grant replication slave, replication client on *.* to 'zmbdpdev'@'%';
grant all privileges on scaffold-ai-assistant_nacos_dev.* to 'zmbdpdev'@'%';
grant all privileges on scaffold-ai-assistant_dev.* to 'zmbdpdev'@'%';
grant all privileges on scaffold-ai-assistant_xxljob_dev.* to 'zmbdpdev'@'%';
GRANT ALL PRIVILEGES ON scaffold-ai-assistant_skywalking_dev.* TO 'zmbdpdev'@'%';
FLUSH PRIVILEGES;

