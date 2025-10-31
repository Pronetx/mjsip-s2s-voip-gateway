
import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import { Construct } from 'constructs';

export interface VoipGatewayEC2StackProps extends cdk.StackProps {
  keyPairName: string;
  vpcId?: string;
}

export class VoipGatewayEC2Stack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: VoipGatewayEC2StackProps) {
    super(scope, id, props);

    // Create a VPC with public subnets
    const vpc = props.vpcId ?
      ec2.Vpc.fromLookup(this, 'VPC', { vpcId: props.vpcId }) :
      new ec2.Vpc(this, 'VPC', {
      maxAzs: 2,
      subnetConfiguration: [
        {
          cidrMask: 24,
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
        }
      ]
    });
    // const vpc = ec2.Vpc.fromLookup(this, 'VPC', {
    //   vpcName: 'my-vpc'
    // });

    const instanceRole = new iam.Role(this, 'TaskRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      inlinePolicies: {
        'BedrockAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: ['bedrock:InvokeModel', 'bedrock:GetModelInvocationLoggingConfiguration', 'bedrock:InvokeModelWithResponseStream'],
              resources: ['*'],
              effect: iam.Effect.ALLOW
            })
          ]
        }),
        'PinpointAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: ['mobiletargeting:SendMessages'],
              resources: ['arn:aws:mobiletargeting:*:*:apps/*/messages'],
              effect: iam.Effect.ALLOW
            })
          ]
        }),
        'CloudWatchLogsAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: [
                'logs:CreateLogGroup',
                'logs:CreateLogStream',
                'logs:PutLogEvents',
                'logs:DescribeLogStreams'
              ],
              resources: ['arn:aws:logs:*:*:log-group:/aws/voip-gateway/*'],
              effect: iam.Effect.ALLOW
            })
          ]
        }),
        'ConnectAccess': new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              actions: ['connect:UpdateContactAttributes'],
              resources: ['*'],
              effect: iam.Effect.ALLOW
            })
          ]
        })
      },
    });

    // Create a security group for the EC2 instance
    const securityGroup = new ec2.SecurityGroup(this, 'VoipGatewaySG', {
      vpc,
      description: 'Allow SSH, SIP, and RTP traffic from Chime Voice Connector',
      allowAllOutbound: true,
    });

    // Chime Voice Connector IP space for us-west-2
    const chimeVoiceConnectorCidr = '99.77.253.0/24';

    // Allow SIP UDP traffic on port 5060 from Chime Voice Connector
    securityGroup.addIngressRule(
      ec2.Peer.ipv4(chimeVoiceConnectorCidr),
      ec2.Port.udp(5060),
      'Allow SIP UDP traffic from Chime Voice Connector'
    );

    // Allow SIP TCP traffic on port 5060 from Chime Voice Connector
    securityGroup.addIngressRule(
      ec2.Peer.ipv4(chimeVoiceConnectorCidr),
      ec2.Port.tcp(5060),
      'Allow SIP TCP traffic from Chime Voice Connector'
    );

    // Allow RTP traffic on ports 10000-20000 from Chime Voice Connector
    securityGroup.addIngressRule(
      ec2.Peer.ipv4(chimeVoiceConnectorCidr),
      ec2.Port.udpRange(10000, 20000),
      'Allow RTP traffic from Chime Voice Connector'
    );

    // Allow SSH traffic for management (consider restricting to your IP)
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(22),
      'Allow SSH traffic for management'
    );

    // Create an EC2 instance in a public subnet
    const instance = new ec2.Instance(this, 'VoipGatewayServer', {
      vpc,
      vpcSubnets: {
        subnetType: ec2.SubnetType.PUBLIC
      },
      instanceType: ec2.InstanceType.of(
        ec2.InstanceClass.T3,
        ec2.InstanceSize.MICRO
      ),
      keyPair: ec2.KeyPair.fromKeyPairName(this, 'VoipGatewayKeypair', props.keyPairName),
      machineImage: ec2.MachineImage.latestAmazonLinux2(),
      securityGroup: securityGroup,
      associatePublicIpAddress: true,
      userData: ec2.UserData.forLinux(),
      role: instanceRole,
    });

    // Add commands to the user data to install Java
    instance.userData.addCommands(
      '#!/bin/bash',
      'yum update -y',
      'yum install -y java-24-amazon-corretto-devel maven git',
      'echo "Dependency installation completed"'
    );

    // Output the public IP of the instance
    new cdk.CfnOutput(this, 'InstancePublicIP', {
      value: instance.instancePublicIp,
      description: 'Public IP address of the EC2 instance',
    });
  }
}