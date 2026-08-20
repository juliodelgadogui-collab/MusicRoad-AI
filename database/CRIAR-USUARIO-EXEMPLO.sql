-- EXEMPLO para servidor próprio. Em cPanel/Plesk, crie o banco e o usuário pelo painel.
-- Troque banco, usuário, host e senha antes de usar.

CREATE DATABASE IF NOT EXISTS musicroad CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'musicroad_app'@'localhost' IDENTIFIED BY 'TROQUE-POR-UMA-SENHA-FORTE-E-ALEATORIA';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON musicroad.* TO 'musicroad_app'@'localhost';
FLUSH PRIVILEGES;

-- Depois que a instalação estiver estabilizada, você pode reduzir privilégios de DDL
-- e usar uma conta separada para futuras migrações, se sua infraestrutura permitir.
