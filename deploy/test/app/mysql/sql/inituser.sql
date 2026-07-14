-- # 1、初始化数据库：创建nacos外接数据库scaffold-ai-assistant_nacos_test和脚手架业务数据库scaffold-ai-assistant_test
-- # 2、创建用户，用户名：zmbdptest 密码：Hf@173503494
-- # 3、授予zmbdptest用户特定权限

CREATE database if NOT EXISTS `scaffold-ai-assistant_nacos_test` default character set utf8mb4 collate utf8mb4_general_ci;
CREATE database if NOT EXISTS `scaffold-ai-assistant_test` default character set utf8mb4 collate utf8mb4_general_ci;
CREATE database if NOT EXISTS `scaffold-ai-assistant_xxljob_test` default character set utf8mb4 collate utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_skywalking_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'zmbdptest'@'%' IDENTIFIED BY 'Hf@173503494';
grant replication slave, replication client on *.* to 'zmbdptest'@'%';

GRANT ALL PRIVILEGES ON scaffold-ai-assistant_nacos_test.* TO  'zmbdptest'@'%';
GRANT ALL PRIVILEGES ON scaffold-ai-assistant_test.* TO  'zmbdptest'@'%';
GRANT ALL PRIVILEGES ON scaffold-ai-assistant_xxljob_test.* TO  'zmbdptest'@'%';
GRANT ALL PRIVILEGES ON scaffold-ai-assistant_skywalking_test.* TO 'zmbdptest'@'%';

FLUSH PRIVILEGES;
