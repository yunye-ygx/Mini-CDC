CREATE TABLE USER (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      username VARCHAR(64) NOT NULL,
                      nickname VARCHAR(64),
                      email VARCHAR(128),
                      STATUS INT NOT NULL DEFAULT 1,
                      created_at DATETIME NOT NULL,
                      updated_at DATETIME NOT NULL
);

