[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/medizininformatik-initiative/cctb/badge)](https://scorecard.dev/viewer/?uri=github.com/medizininformatik-initiative/cctb)

# MII - Clinical Cohort Toolbox (CCTB)

## Usage

### Translator

Example of a translation of a simple CCDL query to HL7 CQL:
```
var c71_1 = TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "C71.1", "Malignant neoplasm of brain");
var mappings = Map.of(c71_1, Mapping.of(c71_1, "Condition"));
var conceptTree = ConceptNode.of(c71_1);
var codeSystemAliases = Map.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "icd10");
var mappingContext = MappingContext.of(mappings, conceptTree, codeSystemAliases);

Library library = Translator.of(mappingContext).toCql(StructuredQuery.of(List.of(
        Group.of(List.of(List.of(ConceptCriterion.of(c71_1)))))));

assertEquals("""
        library Retrieve version '1.0.0'
        using FHIR version '4.0.0'
        include FHIRHelpers version '4.0.0'
                                           
        codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'                
                        
        define InInitialPopulation:
          exists [Condition: Code 'C71.1' from icd10]
        """, library.print(PrintContext.ZERO));
```

### JSON Deserialization of CCDL

```
var mapper = new ObjectMapper();
mapper.readValue("""
        {"inclusionCriteria": [{"criteria": [[{
          "termCodes": [{
            "system": "http://fhir.de/CodeSystem/bfarm/icd-10-gm", 
            "code": "C71.1",
            "display": "Malignant neoplasm of brain"
          }]
        }], [{
          "termCodes": [{
            "system": "http://loinc.org", 
            "code": "76689-9",
            "display": "Sex assigned at birth"
          }],
          "valueFilter": {
            "type": "concept",
            "selectedConcepts": [
              {
                "system": "http://hl7.org/fhir/administrative-gender",
                "code": "male",
                "display": "Male"
              },
              {
                "system": "http://hl7.org/fhir/administrative-gender",
                "code": "female",
                "display": "Female"
              }
            ]
          }
        }]]}]}
        """, StructuredQuery.class);
```

## License

Copyright [yyyy] [name of copyright owner]

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "
AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
