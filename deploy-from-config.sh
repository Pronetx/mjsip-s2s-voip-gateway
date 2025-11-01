#!/bin/bash

set -e

CONFIG_FILE="deployment-config.json"
KEY_PATH="/Users/yasser/Downloads/mjsip_s2s_gateway.pem"

echo "======================================"
echo "VoIP Gateway Automated Deployment"
echo "======================================"
echo ""

# Check if config file exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ Error: $CONFIG_FILE not found"
    exit 1
fi

# Extract values from config using jq or python
if command -v jq &> /dev/null; then
    REGION=$(jq -r '.aws.region' $CONFIG_FILE)
    INSTANCE_TYPE=$(jq -r '.ec2.instanceType' $CONFIG_FILE)
    KEY_NAME=$(jq -r '.ec2.keyPairName' $CONFIG_FILE)
    ELASTIC_IP=$(jq -r '.ec2.elasticIp' $CONFIG_FILE)
    ELASTIC_IP_ALLOC=$(jq -r '.ec2.elasticIpAllocationId' $CONFIG_FILE)
    SG_NAME=$(jq -r '.ec2.securityGroup.name' $CONFIG_FILE)
    SG_ID=$(jq -r '.ec2.securityGroup.groupId' $CONFIG_FILE)
    IAM_ROLE=$(jq -r '.ec2.iamRole.name' $CONFIG_FILE)
    IAM_PROFILE=$(jq -r '.ec2.iamRole.instanceProfileName' $CONFIG_FILE)
    PINPOINT_APP_ID=$(jq -r '.aws_services.pinpoint.applicationId' $CONFIG_FILE)
    LAMBDA_URL=$(jq -r '.aws_services.lambda.addressValidation.url' $CONFIG_FILE)
else
    echo "❌ Error: jq not found. Please install jq or use python to parse JSON"
    exit 1
fi

echo "Configuration loaded from $CONFIG_FILE"
echo "Region: $REGION"
echo "Instance Type: $INSTANCE_TYPE"
echo "Elastic IP: $ELASTIC_IP"
echo ""

# Step 1: Check if security group exists, create if not
echo "[1/10] Checking security group..."
if aws ec2 describe-security-groups --region $REGION --group-ids $SG_ID &>/dev/null; then
    echo "✓ Security group $SG_ID exists"
else
    echo "Creating security group $SG_NAME..."
    SG_ID=$(aws ec2 create-security-group \
        --region $REGION \
        --group-name $SG_NAME \
        --description "Security group for MjSip S2S VoIP Gateway" \
        --query 'GroupId' \
        --output text)
    
    # Add rules
    aws ec2 authorize-security-group-ingress --region $REGION --group-id $SG_ID --protocol tcp --port 22 --cidr 0.0.0.0/0
    aws ec2 authorize-security-group-ingress --region $REGION --group-id $SG_ID --protocol udp --port 5060 --cidr 0.0.0.0/0
    aws ec2 authorize-security-group-ingress --region $REGION --group-id $SG_ID --protocol udp --port 10000-20000 --cidr 0.0.0.0/0
    echo "✓ Security group created: $SG_ID"
fi

# Step 2: Check if IAM role exists, create if not
echo "[2/10] Checking IAM role..."
if aws iam get-role --role-name $IAM_ROLE --region $REGION &>/dev/null; then
    echo "✓ IAM role $IAM_ROLE exists"
else
    echo "Creating IAM role $IAM_ROLE..."
    aws iam create-role --role-name $IAM_ROLE --region $REGION \
        --assume-role-policy-document '{
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow",
                "Principal": {"Service": "ec2.amazonaws.com"},
                "Action": "sts:AssumeRole"
            }]
        }'
    
    aws iam put-role-policy --role-name $IAM_ROLE --policy-name NovaVoipGatewayPolicy --region $REGION \
        --policy-document '{
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Action": [
                        "bedrock:InvokeModel",
                        "bedrock:InvokeModelWithResponseStream",
                        "bedrock:GetModelInvocationLoggingConfiguration"
                    ],
                    "Resource": "*"
                },
                {
                    "Effect": "Allow",
                    "Action": [
                        "logs:CreateLogGroup",
                        "logs:CreateLogStream",
                        "logs:PutLogEvents",
                        "logs:DescribeLogStreams"
                    ],
                    "Resource": "*"
                },
                {
                    "Effect": "Allow",
                    "Action": ["mobiletargeting:*", "connect:*"],
                    "Resource": "*"
                },
                {
                    "Effect": "Allow",
                    "Action": ["lambda:InvokeFunction"],
                    "Resource": "*"
                }
            ]
        }'
    
    aws iam create-instance-profile --instance-profile-name $IAM_PROFILE --region $REGION
    aws iam add-role-to-instance-profile --instance-profile-name $IAM_PROFILE --role-name $IAM_ROLE --region $REGION
    sleep 10  # Wait for IAM propagation
    echo "✓ IAM role created"
fi

# Step 3: Launch EC2 instance
echo "[3/10] Launching EC2 instance..."
INSTANCE_ID=$(aws ec2 run-instances --region $REGION \
    --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
    --instance-type $INSTANCE_TYPE \
    --key-name $KEY_NAME \
    --security-group-ids $SG_ID \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=mjsip-s2s-gateway}]" \
    --query 'Instances[0].InstanceId' \
    --output text)

echo "✓ Instance launched: $INSTANCE_ID"

# Step 4: Wait for instance to be running
echo "[4/10] Waiting for instance to be running..."
aws ec2 wait instance-running --region $REGION --instance-ids $INSTANCE_ID
echo "✓ Instance is running"

# Step 5: Associate Elastic IP
echo "[5/10] Associating Elastic IP..."
aws ec2 associate-address --region $REGION \
    --instance-id $INSTANCE_ID \
    --allocation-id $ELASTIC_IP_ALLOC
echo "✓ Elastic IP $ELASTIC_IP associated"

# Step 6: Attach IAM instance profile
echo "[6/10] Attaching IAM instance profile..."
sleep 5
aws ec2 associate-iam-instance-profile --region $REGION \
    --instance-id $INSTANCE_ID \
    --iam-instance-profile "Arn=arn:aws:iam::322081704783:instance-profile/$IAM_PROFILE"
echo "✓ IAM instance profile attached"

# Step 7: Wait for SSH to be ready
echo "[7/10] Waiting for SSH to be ready..."
sleep 30
for i in {1..30}; do
    if ssh -i "$KEY_PATH" -o StrictHostKeyChecking=no -o ConnectTimeout=5 ec2-user@$ELASTIC_IP "echo ready" &>/dev/null; then
        echo "✓ SSH is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ SSH connection timeout"
        exit 1
    fi
    sleep 10
done

# Step 8: Install Java and dependencies
echo "[8/10] Installing Java and dependencies..."
ssh -i "$KEY_PATH" ec2-user@$ELASTIC_IP "sudo yum install -y java-17-amazon-corretto-headless"
echo "✓ Java 17 installed"

# Step 9: Deploy application
echo "[9/10] Deploying application..."
./deploy-to-ec2.sh $ELASTIC_IP
echo "✓ Application deployed"

# Step 10: Configure systemd service
echo "[10/10] Configuring systemd service..."
ssh -i "$KEY_PATH" ec2-user@$ELASTIC_IP "sudo tee /etc/systemd/system/nova-voip-gateway.service > /dev/null" << SERVICEEOF
[Unit]
Description=Nova VoIP Gateway Service
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/mjsip-s2s-voip-gateway
Environment="PINPOINT_APPLICATION_ID=$PINPOINT_APP_ID"
Environment="ADDRESS_VALIDATION_LAMBDA_URL=$LAMBDA_URL"
Environment="CLOUDWATCH_LOGGING_ENABLED=true"
Environment="AWS_REGION=$REGION"
ExecStart=/usr/bin/java -jar /home/ec2-user/mjsip-s2s-voip-gateway/s2s-voip-gateway-0.6-SNAPSHOT.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=nova-voip-gateway

[Install]
WantedBy=multi-user.target
SERVICEEOF

ssh -i "$KEY_PATH" ec2-user@$ELASTIC_IP "sudo systemctl daemon-reload && sudo systemctl enable nova-voip-gateway && sudo systemctl start nova-voip-gateway"

echo "✓ Service configured and started"
echo ""
echo "======================================"
echo "Deployment Complete!"
echo "======================================"
echo ""
echo "Instance ID: $INSTANCE_ID"
echo "IP Address: $ELASTIC_IP"
echo ""
echo "Verify deployment:"
echo "  ssh -i $KEY_PATH ec2-user@$ELASTIC_IP"
echo "  sudo systemctl status nova-voip-gateway"
echo ""
echo "Test call: +14432304260"
echo ""
