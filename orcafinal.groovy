#!groovy

/*************************************************************************
 * Orca Security Scan - Standalone Test Pipeline
 *
 * Purpose:
 *   - Pulls an existing Docker image from ECR for the given branch/env
 *   - Runs Orca security scan on it
 *   - Sends vulnerabilities to the correct Orca project key
 *
 * Branches Supported:
 *   - development  → Orca project: development
 *   - staging      → Orca project: staging
 *   - qat          → Orca project: qat
 *   - master       → Orca project: prod
 *
 * Usage:
 *   - Trigger this pipeline manually from Jenkins
 *   - Select the branch and provide the image tag to scan
 *   - Once all environments are validated, the scan block will be
 *     merged into the main pipeline
 *************************************************************************/

@Library('kxlib@ashishvorca') _

pipeline {
  agent {
    kubernetes {
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: docker
    image: 035592488042.dkr.ecr.us-west-2.amazonaws.com/jenkins-agents:docker-orcacli
    tty: true
    securityContext:
      privileged: true
      capabilities:
        drop:
        - ALL
        add:
        - NET_BIND_SERVICE
  nodeSelector:
    type: karpenter
  securityContext:
    seLinuxOptions:
      type: spc_t
      """
    }
  }

  parameters {
    // The specific image tag you want to scan e.g. dev-123, stage-456, qat-789, prod-101
    string(name: 'IMAGE_TAG', defaultValue: '', description: 'Docker image tag to scan (e.g. dev-123). Leave blank to use latest build number.')
    string(name: 'PROJECT_NAME', defaultValue: '', description: 'The ECR project/service name to scan (e.g. my-service)')
  }

  environment {
    ECR_HOST        = '035592488042.dkr.ecr.us-west-2.amazonaws.com'
    ORCA_API_TOKEN  = credentials('orca-api-token')
  }

  stages {

    // ------------------------------------------------------------------
    // STAGE 1 - Setup
    // Validates inputs and resolves the Orca project key for the branch
    // ------------------------------------------------------------------
    stage('Setup') {
      steps {
        container('docker') {
          script {

            // Validate required parameters
            if (!params.PROJECT_NAME?.trim()) {
              error("ABORTED -> PROJECT_NAME parameter is required.")
            }

            // Resolve Orca project key from branch name
            // Matches Orca projects: development, staging, qat, prod
            if (env.BRANCH_NAME == 'development' || env.BRANCH_NAME == 'development_new') {
              env.ORCA_PROJECT_KEY = 'development'
            } else if (env.BRANCH_NAME == 'staging') {
              env.ORCA_PROJECT_KEY = 'staging'
            } else if (env.BRANCH_NAME == 'qat') {
              env.ORCA_PROJECT_KEY = 'qat'
            } else if (env.BRANCH_NAME == 'master') {
              env.ORCA_PROJECT_KEY = 'prod'
            } else {
              error("ABORTED -> Branch [${env.BRANCH_NAME}] is not configured for Orca scanning. Supported: development, development_new, staging, qat, master")
            }

            // Resolve image tag - use parameter if provided, else fall back to build number
            env.SCAN_IMAGE_TAG = params.IMAGE_TAG?.trim() ? params.IMAGE_TAG.trim() : "${env.BUILD_NUMBER}"
            env.FULL_IMAGE = "${env.ECR_HOST}/service_images/${params.PROJECT_NAME}:${env.SCAN_IMAGE_TAG}"

            echo "============================================"
            echo "Branch          : ${env.BRANCH_NAME}"
            echo "Project Name    : ${params.PROJECT_NAME}"
            echo "Image to Scan   : ${env.FULL_IMAGE}"
            echo "Orca Project Key: ${env.ORCA_PROJECT_KEY}"
            echo "============================================"
          }
        }
      }
    }

    // ------------------------------------------------------------------
    // STAGE 2 - Orca Security Scan
    // Logs into ECR, pulls the image, runs orca-cli scan
    // ------------------------------------------------------------------
    stage('Orca Security Scan') {
      steps {
        container('docker') {
          script {
            withCredentials([
              [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_Credentials', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
            ]) {
              // Login to ECR to pull the image
              sh """
                aws configure set default.region us-west-2
                aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin ${env.ECR_HOST}
              """

              // Pull the image from ECR
              echo "Pulling image: ${env.FULL_IMAGE}"
              sh "docker pull ${env.FULL_IMAGE}"

              // Run Orca scan and send results to the correct project
              echo "Running Orca scan for project key: ${env.ORCA_PROJECT_KEY}"
              sh """
                orca-cli image scan \
                  --project-key ${env.ORCA_PROJECT_KEY} \
                  --api-token ${ORCA_API_TOKEN} \
                  ${env.FULL_IMAGE}
              """

              // Clean up pulled image to save disk space
              sh "docker rmi -f ${env.FULL_IMAGE} || true"
            }
          }
        }
      }
    }

  }

  // ------------------------------------------------------------------
  // POST - Notify result via Teams
  // ------------------------------------------------------------------
  post {
    success {
      echo "Orca scan completed successfully for [${env.FULL_IMAGE}] → Orca project [${env.ORCA_PROJECT_KEY}]"
    }
    failure {
      echo "Orca scan FAILED for [${env.FULL_IMAGE}] → Orca project [${env.ORCA_PROJECT_KEY}]"
    }
    cleanup {
      cleanWs()
    }
  }
}
