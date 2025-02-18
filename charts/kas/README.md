# KAS

KAS needs at least 4 keys (2 pairs, one RSA and one EC pair) to run.

These keys are important, as they are the "keys to the kingdom", so to speak - possessing them might allow data access by unauthorized parties.

Also, if the keys change, TDF files created with the previous set will no longer be decryptable.

Therefore, it is strongly recommended that these keys be generated and managed properly in a production environment.

1. Install Chart (will generate throwaway non-production KAS keys if none provided at install time, not recommended or supported for production): 

```sh
helm upgrade --install kas .
```

If you wish to provide and manage your own KAS keys (recommended), you may do so by either:

1. Creating/managing your own named K8S Secret in the chart namespace in the form described by [](./templates/secrets.yaml), and setting `kas.externalEnvSecretName` accordingly:
``` sh
helm upgrade --install kas --set externalEnvSecretName=<Secret-with-rsa-and-ec-keypairs> .
```

1. Supplying each private/public key as a values override, e.g:

``` sh
helm upgrade --install kas \
  --set envConfig.attrAuthorityCert=$ATTR_AUTHORITY_CERTIFICATE \
  --set envConfig.ecCert=$KAS_EC_SECP256R1_CERTIFICATE \
  --set envConfig.cert=$KAS_CERTIFICATE \
  --set envConfig.ecPrivKey=$KAS_EC_SECP256R1_PRIVATE_KEY \
  --set envConfig.privKey=$KAS_PRIVATE_KEY .
```
