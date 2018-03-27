import React from 'react';
import { BallPulseLoading } from '../LoadingIcon/LoadingIcon';
import { Link } from "react-router-dom";
import { PaginationButtons, EntriesPerPageSelector } from "../Pagination";
import { Table, Button } from 'react-bootstrap';
import { Card } from "../Cards";
import { connect } from "react-redux";
import { fetchApplications, setLoading, toPage, updateApplicationsPerPage, updateApplications } from '../../Actions/Applications';
import { updatePageTitle } from "../../Actions/Status";

class Applications extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            lastSorting: {
                name: "name",
                asc: true,
            }
        };
        this.sortByName = this.sortByName.bind(this);
        this.sortByVisibility = this.sortByVisibility.bind(this);
        this.sortByVersion = this.sortByVersion.bind(this);
        this.sortingIcon = this.sortingIcon.bind(this);
        const { dispatch } = this.props;
        dispatch(updatePageTitle(this.constructor.name));
        dispatch(setLoading(true));
        dispatch(fetchApplications());
    }

    sortingIcon(name) {
        if (this.state.lastSorting.name === name) {
            return this.state.lastSorting.asc ? "ion-chevron-down" : "ion-chevron-up";
        }
        return "";
    }

    sortByVisibility() {
        let apps = this.props.applications.slice();
        let asc = !this.state.lastSorting.asc;
        let order = asc ? 1 : -1;
        apps.sort((a, b) => {
            return (a.isPrivate - b.isPrivate) * order;
        });
        this.setState(() => ({
            lastSorting: {
                name: "visibility",
                asc: asc,
            },
        }));
        this.props.dispatch(updateApplications(apps));
    }

    sortByName() {
        let apps = this.props.applications.slice();
        let asc = !this.state.lastSorting.asc;
        let order = asc ? 1 : -1;
        apps.sort((a, b) => {
            return a.prettyName.localeCompare(b.prettyName) * order;
        });
        this.setState(() => ({
            lastSorting: {
                name: "name",
                asc: asc,
            },
        }));
        this.props.dispatch(updateApplications(apps));
    }

    sortByVersion() {
        let apps = this.props.applications.slice();
        let asc = !this.state.lastSorting.asc;
        let order = asc ? 1 : -1;
        apps.sort((a, b) => {
            return a.info.version.localeCompare(b.info.version) * order;
        });
        this.setState(() => ({
            lastSorting: {
                name: "version",
                asc: asc,
            }
        }));
        this.props.dispatch(updateApplications(apps));
    }

    render() {
        const { applications, loading, applicationsPerPage, currentApplicationsPage, dispatch } = this.props;
        const currentlyShownApplications = applications.slice(currentApplicationsPage * applicationsPerPage, currentApplicationsPage * applicationsPerPage + applicationsPerPage);
        const totalPages = Math.ceil(applications.length / applicationsPerPage);
        return (
            <section>
                <div className="container" style={{ "marginTop": "60px" }}>
                    <div>
                        <BallPulseLoading loading={!applications.length} />
                        <Card>
                            <div className="card-body">
                                <Table responsive className="table table-hover mv-lg">
                                    <thead>
                                        <tr>
                                            <th onClick={() => this.sortByVisibility()}><span className="text-left">Visibility<span
                                                className={`pull-right ${this.sortingIcon("visibility")}`} /></span></th>
                                            <th onClick={() => this.sortByName()}><span className="text-left">Application Name<span
                                                className={`pull-right ${this.sortingIcon("name")}`} /></span></th>
                                            <th onClick={() => this.sortByVersion()}>
                                                <span className="text-left">Version
                                                    <span className={`pull-right ${this.sortingIcon("version")}`} />
                                                </span>
                                            </th>
                                            <th />
                                        </tr>
                                    </thead>
                                    <ApplicationsList applications={currentlyShownApplications} />
                                </Table>
                            </div>
                        </Card>
                        <PaginationButtons
                            toPage={(page) => dispatch(toPage(page))}
                            currentPage={currentApplicationsPage}
                            totalPages={totalPages}
                        />
                        <EntriesPerPageSelector
                            entriesPerPage={applicationsPerPage}
                            handlePageSizeSelection={(size) => dispatch(updateApplicationsPerPage(size))}
                            totalPages={totalPages}
                        >
                            Applications per page
                        </EntriesPerPageSelector>
                    </div>
                </div>
            </section>);
    }
}

const ApplicationsList = ({ applications }) => {
    let applicationsList = applications.map((app, index) =>
        <SingleApplication key={index} app={app} />
    );
    return (
        <tbody>
            {applicationsList}
        </tbody>)
};

const SingleApplication = ({ app }) => (
    <tr className="gradeA row-settings">
        <PrivateIcon isPrivate={app.info.isPrivate} />
        <td title={app.description}>{app.prettyName}</td>
        <td title={app.description}>{app.info.version}</td>
        <th>
            <Link to={`/applications/${app.info.name}/${app.info.version}/`}>
                <Button className="btn btn-info">Run</Button>
            </Link>
        </th>
    </tr>
);

const PrivateIcon = ({ isPrivate }) => {
    if (isPrivate) {
        return (
            <td title="The app is private and can only be seen by the creator and people it was shared with">
                <em className="ion-locked" /></td>
        )
    } else {
        return (<td title="The application is openly available for everyone"><em className="ion-unlocked" /></td>)
    }
};

const mapStateToProps = (state) => {
    return { applications, loading, applicationsPerPage, currentApplicationsPage } = state.applications;
}

export default connect(mapStateToProps)(Applications);