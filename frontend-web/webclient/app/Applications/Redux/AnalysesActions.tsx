import {Cloud} from "Authentication/SDUCloudObject";
import {SET_ANALYSES_LOADING, RECEIVE_ANALYSES, SET_ANALYSES_ERROR} from "./AnalysesReducer";
import {Page, ReceivePage, SetLoadingAction} from "Types";
import {Analysis} from "..";
import {snackbarStore} from "Snackbar/SnackbarStore";
import {errorMessageOrDefault} from "UtilityFunctions";
import {Action} from "redux";


export type AnalysesActions = ReceiveAnalysesProps | AnalysesError | AnalysesLoading;

/**
 * Fetches a page of analyses based on the itemsPerPage and page provided
 * @param {number} itemsPerPage number of items the retrieved page should contain
 * @param {number} page the page number to be retrieved
 */
export const fetchAnalyses = async (itemsPerPage: number, page: number): Promise<ReceiveAnalysesProps | AnalysesError> => {
    try {
        const {response} = await Cloud.get(`/hpc/jobs/?itemsPerPage=${itemsPerPage}&page=${page}`);
        return receiveAnalyses(response);
    } catch (e) {
        snackbarStore.addFailure(errorMessageOrDefault(e, "Retrieval of analyses failed, please try again later."));
        return setError()
    }
}

type ReceiveAnalysesProps = ReceivePage<typeof RECEIVE_ANALYSES, Analysis>
/**
 * Returns an action containing the page retrieved
 * @param {Page<Analysis>} page contains the analyses, pageNumber and items per page
 */
const receiveAnalyses = (page: Page<Analysis>): ReceiveAnalysesProps => ({
    type: RECEIVE_ANALYSES,
    payload: {page}
});

type AnalysesError = Action<typeof SET_ANALYSES_ERROR>
/**
 * Action used to represent an error has occurred.
 * @returns {AnalysesError}
 */
export const setError = (): AnalysesError => ({
    type: SET_ANALYSES_ERROR
});


type AnalysesLoading = SetLoadingAction<typeof SET_ANALYSES_LOADING>
/**
 * Sets whether or not the component is loading
 * @param {boolean} loading - whether or not it is loading
 */
export const setLoading = (loading: boolean): AnalysesLoading => ({
    type: SET_ANALYSES_LOADING,
    payload: {loading}
});