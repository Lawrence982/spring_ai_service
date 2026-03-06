pipeline {
    agent {
        label 'built-in' // Это заставит билд выполняться на мастере
    }

    tools {
        // Имя должно быть таким же, как в Global Tool Configuration
        maven 'maven-3'
    }

    environment {
        // Имя твоего образа
        IMAGE_NAME = "llm-host-service"
        IMAGE_TAG  = "${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: 'feature/llm-with-tools', url: 'https://github.com/Lawrence982/spring_ai_service'
            }
        }
        stage('Build') {
            steps {
                sh 'mvn clean install'
            }
        }
        stage('Test') {
            steps {
                sh 'mvn test'
            }
        }
        stage('Docker Build') {
            steps {
                script {
                    sh 'docker build -t ${IMAGE_NAME}:${IMAGE_TAG} -f deployments/docker/Dockerfile .'
                }
            }
        }
        stage('Cleanup Old Images') {
            steps {
                script {
                    // Оставляем последние 3 образа llm-host-service, удаляем остальные
                    sh """
                        docker images ${IMAGE_NAME} --format '{{.Tag}}' \
                            | grep -E '^[0-9]+\$' \
                            | sort -rn \
                            | tail -n +4 \
                            | xargs -r -I{} docker rmi ${IMAGE_NAME}:{} || true
                    """
                }
            }
        }
        stage('Deploy Services') {
            steps {
                script {
                    // Мапа: имя_чарта -> неймспейс
                    def serviceConfig = [
                        'ollama': 'ollama',
                        'postgres': 'postgres',
                        'llm-host-service': 'default'
                    ]

                    serviceConfig.each { chartName, targetNamespace ->
                        // Формируем доп. параметры для тега
                        def overrideTag = (chartName == "llm-host-service") ? "--set IMAGE.TAG=${IMAGE_TAG}" : ""

                        echo "Deploying ${chartName} to namespace ${targetNamespace}..."

                        sh """
                            /var/jenkins_home/helm upgrade --install ${chartName} ./deployments/umbrella-chart/charts/${chartName} \
                            ${overrideTag} \
                            --create-namespace \
                            --namespace ${targetNamespace} \
                            --wait
                        """
                    }
                }
            }
        }
    }
}