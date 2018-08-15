import { infoNotification } from "UtilityFunctions";
import { Application, ParameterTypes } from "Applications";
import Cloud from "Authentication/lib";
import { Page } from "Types";


export const hpcJobQuery = (id: string, stdoutLine: number, stderrLine: number, stdoutMaxLines: number = 1000, stderrMaxLines: number = 1000) =>
    `hpc/jobs/follow/${id}?stdoutLineStart=${stdoutLine}&stdoutMaxLines=${stdoutMaxLines}&stderrLineStart=${stderrLine}&stderrMaxLines=${stderrMaxLines}`;

/**
* //FIXME Missing backend functionality
* Favorites an application. 
* @param {Application} Application the application to be favorited
* @param {Cloud} cloud The cloud instance for requests
*/
export const favoriteApplicationFromPage = (application: Application, page: Page<Application>, cloud: Cloud): Page<Application> => {
    const a = page.items.find(it => it.description.info.name === application.description.info.name);
    a.favorite = !a.favorite;
    infoNotification("Backend functionality for favoriting applications missing");
    return page;
    /*  
    const { info } = a.description;
    if (a.favorite) {
        cloud.post(`/applications/favorite?name=${info.name}&version=${info.name}`, {})
    } else {
        cloud.delete(`/applications/favorite?name=${info.name}&version=${info.name}`, {})
    } 
    */
}

export const extractParametersVersion1 = (parameters, allowedParameterKeys, siteVersion: number) => {
    let extractedParameters = {};
    if (siteVersion === 1) {
        allowedParameterKeys.forEach(par => {
            if (parameters[par.name] !== undefined) {
                if (compareType(par.type, parameters[par.name])) {
                    extractedParameters[par.name] = parameters[par.name];
                }
            }
        });
    }
    return extractedParameters;
}

const compareType = (type, parameter): boolean => {
    switch (type) {
        case ParameterTypes.Boolean:
            return typeof parameter === "boolean";
        case ParameterTypes.Integer:
            return typeof parameter === "number" && parameter % 1 === 0;
        case ParameterTypes.FloatingPoint:
            return typeof parameter === "number";
        case ParameterTypes.Text:
            return typeof parameter === "string";
        case ParameterTypes.InputDirectory:
        case ParameterTypes.InputFile:
            return typeof parameter.destination === "string" && typeof parameter.source === "string";
        default:
            return false;
    }
}